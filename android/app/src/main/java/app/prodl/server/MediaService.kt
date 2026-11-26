package app.prodl.server

import android.util.Log
import app.prodl.core.ConvertRequest
import app.prodl.core.CookieStore
import app.prodl.core.DownloadRequest
import app.prodl.core.FfmpegArgs
import app.prodl.core.FfmpegRunner
import app.prodl.core.FileRef
import app.prodl.core.FormatSelector
import app.prodl.core.InfoResponse
import app.prodl.core.MimeTypes
import app.prodl.core.NpFormatId
import app.prodl.core.NpSelection
import app.prodl.core.NpStream
import app.prodl.core.NewPipeEngine
import app.prodl.core.NewPipeMapper
import app.prodl.core.ProgressEvent
import app.prodl.core.ProgressLine
import app.prodl.core.SearchResponse
import app.prodl.core.ServerConfig
import app.prodl.core.SponsorBlockClient
import app.prodl.core.SponsorBlockInfo
import app.prodl.core.TaskStatus
import app.prodl.core.YtDlp
import app.prodl.core.YtDlpException
import app.prodl.core.YtDlpJson
import app.prodl.core.cleanError
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Everything that talks to the extraction engines and ffmpeg. Routes stay thin
 * and only validate input, mirroring the Python backend's service layer.
 *
 * Engine routing (API_SPEC.md §5–7):
 * - YouTube → NewPipeExtractor first, yt-dlp if it is not ready or fails.
 * - Everything else → yt-dlp.
 * A `format_id` starting with `np:` can only be served by NewPipeExtractor; any
 * other selector can only be served by yt-dlp.
 */
class MediaService(
    private val config: ServerConfig,
    private val tasks: TaskManager,
    private val yt: YtDlp,
    private val newpipe: NewPipeEngine,
    private val ffmpeg: FfmpegRunner,
    private val cookies: CookieStore,
    private val sponsor: SponsorBlockClient,
    private val scope: CoroutineScope,
) {
    suspend fun search(query: String, limit: Int): SearchResponse {
        val effectiveLimit = limit.coerceIn(1, config.searchLimitMax)
        newPipeOrNull { newpipe.search(query, effectiveLimit) }
            ?.takeIf { it.count > 0 }
            ?.let { return it }

        val json = yt.dumpJson(
            "ytsearch$effectiveLimit:$query",
            listOf("--flat-playlist" to null),
        )
        return YtDlpJson.search(query, effectiveLimit, json)
    }

    suspend fun info(url: String, withSponsorBlock: Boolean, categories: List<String>?): InfoResponse {
        val viaNewPipe = if (newpipe.supports(url)) newPipeOrNull { newpipe.info(url) } else null
        if (viaNewPipe != null) {
            if (!withSponsorBlock) return viaNewPipe
            val videoId = viaNewPipe.id
            return viaNewPipe.copy(
                sponsorblock = if (videoId != null) {
                    sponsor.segments(videoId, categories)
                } else {
                    SponsorBlockInfo(requested = true, error = "No video id")
                },
            )
        }

        val cookieFile = cookies.fileOrNull()
        val extras = buildList {
            add("--no-playlist" to null)
            if (cookieFile != null) add("--cookies" to cookieFile.absolutePath)
        }
        val json = yt.dumpJson(url, extras)
        val info = YtDlpJson.info(url, json, cookiesUsed = cookieFile != null)
        if (!withSponsorBlock) return info
        val videoId = YtDlpJson.videoId(json)
            ?: return info.copy(sponsorblock = SponsorBlockInfo(requested = true, error = "No video id"))
        return info.copy(sponsorblock = sponsor.segments(videoId, categories))
    }

    fun startDownload(request: DownloadRequest): Task =
        if (wantsNewPipe(request)) startNewPipeDownload(request) else startYtDlpDownload(request)

    /** `np:` ids, and requests with no id at all, belong to NewPipeExtractor. */
    private fun wantsNewPipe(request: DownloadRequest): Boolean {
        if (!newpipe.supports(request.url)) return false
        val id = request.formatId?.trim().orEmpty()
        if (id.isEmpty()) return true
        return NpFormatId.parse(id) != null
    }

    /**
     * Direct-stream download: one muxed file, or a video track plus an audio
     * track joined by ffmpeg. No Python involved, so this path works while
     * yt-dlp is still unpacking — or broken beyond repair.
     */
    private fun startNewPipeDownload(request: DownloadRequest): Task {
        val task = tasks.create("download")
        task.job = scope.launch(Dispatchers.IO) {
            var workDir: File? = null
            try {
                if (!config.ensureDirectories()) throw YtDlpException("Download directory is not writable")
                task.publish(
                    ProgressEvent(status = TaskStatus.DOWNLOADING, percent = 0.0, message = "Resolving media")
                )

                val media = newpipe.media(request.url)
                val selection = NewPipeMapper.select(media, request.formatId, request.audioOnly)
                    ?: throw YtDlpException("This video offers no stream for the selected format")
                task.title = media.title

                workDir = File(config.downloadDir, ".prodl-${task.id}").apply { mkdirs() }
                val videoFile = selection.video?.let { stream ->
                    val top = if (selection.audio == null) 100.0 else 70.0
                    fetchStream(task, stream, File(workDir, "video.${stream.ext}"), 0.0, top)
                }
                val audioFile = selection.audio?.let { stream ->
                    fetchStream(task, stream, File(workDir, "audio.${stream.ext}"), if (videoFile == null) 0.0 else 70.0, 100.0)
                }
                if (task.cancelRequested.get()) {
                    task.markCanceled()
                    return@launch
                }

                val artifact = when {
                    videoFile == null && audioFile != null -> finishAudioOnly(task, request, audioFile, workDir)
                    videoFile != null && audioFile == null -> videoFile
                    videoFile != null && audioFile != null -> muxTracks(task, videoFile, audioFile, workDir, selection)
                    else -> throw YtDlpException("Nothing to download")
                }

                task.publish(
                    ProgressEvent(status = TaskStatus.POSTPROCESSING, percent = 100.0, message = "Finishing file")
                )
                val target = moveIntoLibrary(artifact, selection.title, media.id)
                task.finish(
                    FileRef(
                        filename = target.name,
                        filesize = target.length(),
                        downloadUrl = "/api/files/${task.id}",
                        mediaType = MimeTypes.guess(target.name),
                    ),
                    target,
                )
            } catch (canceled: CancellationException) {
                if (!task.isDone) task.markCanceled()
            } catch (t: Throwable) {
                Log.w(TAG, "newpipe download ${task.id} failed", t)
                if (task.cancelRequested.get()) task.markCanceled() else task.fail(cleanError(t.message ?: t.toString()))
            } finally {
                workDir?.deleteRecursively()
            }
        }
        return task
    }

    /** Download one stream, mapping its byte progress into [from]–[to] percent. */
    private suspend fun fetchStream(
        task: Task,
        stream: NpStream,
        dest: File,
        from: Double,
        to: Double,
    ): File {
        var lastPublishAt = 0L
        var lastBytes = 0L
        var lastSampleAt = System.currentTimeMillis()
        newpipe.fetch(stream, dest) { written, total ->
            if (task.cancelRequested.get()) throw CancellationException("canceled")
            val now = System.currentTimeMillis()
            val finished = total > 0 && written >= total
            if (now - lastPublishAt < 400 && !finished) return@fetch
            lastPublishAt = now
            val elapsed = (now - lastSampleAt) / 1000.0
            val speed = if (elapsed > 0) ((written - lastBytes) / elapsed).coerceAtLeast(0.0) else 0.0
            lastSampleAt = now
            lastBytes = written
            val fraction = if (total > 0) (written.toDouble() / total).coerceIn(0.0, 1.0) else 0.0
            val remaining = if (total > 0 && speed > 1) ((total - written) / speed).toLong() else 0L
            task.publish(
                ProgressEvent(
                    status = TaskStatus.DOWNLOADING,
                    percent = from + (to - from) * fraction,
                    downloadedBytes = written,
                    totalBytes = total.takeIf { it > 0 },
                    speed = speed.takeIf { it > 0 },
                    speedStr = speed.takeIf { it > 0 }?.let { ProgressLine.formatSpeed(it) },
                    eta = remaining.takeIf { it > 0 },
                    message = if (total > 0) "Downloading" else "Downloading (size unknown)",
                )
            )
        }
        if (dest.length() == 0L) throw YtDlpException("The media server sent an empty file")
        return dest
    }

    /** Audio-only download: keep the track when the container matches, else transcode. */
    private suspend fun finishAudioOnly(
        task: Task,
        request: DownloadRequest,
        audioFile: File,
        workDir: File,
    ): File {
        val wanted = FormatSelector.audioFormat(request.audioFormat)
        if (MimeTypes.extensionOf(audioFile.name) == wanted) return audioFile

        task.publish(
            ProgressEvent(status = TaskStatus.POSTPROCESSING, percent = 95.0, message = "Converting to $wanted")
        )
        val binary = ffmpeg.binary() ?: throw YtDlpException("ffmpeg is not available on this device")
        val output = File(workDir, "audio.$wanted")
        val exit = ffmpeg.run(
            binary,
            FfmpegArgs.build(
                input = audioFile,
                output = output,
                format = wanted,
                audioBitrate = "${FormatSelector.audioQuality(request.audioQuality)}k",
            ),
            onProcess = { task.ffmpegProcess = it },
        ) { }
        if (exit != 0 || !output.isFile || output.length() == 0L) {
            throw YtDlpException("ffmpeg could not convert the audio track (exit $exit)")
        }
        audioFile.delete()
        return output
    }

    /** Join video-only + audio-only without re-encoding, into a container that fits. */
    private suspend fun muxTracks(
        task: Task,
        videoFile: File,
        audioFile: File,
        workDir: File,
        selection: NpSelection,
    ): File {
        val binary = ffmpeg.binary() ?: throw YtDlpException(
            "ffmpeg is required to join separate video and audio streams on this device"
        )
        task.publish(
            ProgressEvent(status = TaskStatus.POSTPROCESSING, percent = 80.0, message = "Muxing video and audio")
        )
        val ext = FfmpegArgs.muxExtension(
            MimeTypes.extensionOf(videoFile.name),
            MimeTypes.extensionOf(audioFile.name),
        )
        val output = File(workDir, "muxed.$ext")
        var lastPublishAt = 0L
        val duration = ffmpeg.durationSeconds(videoFile)
        val exit = ffmpeg.run(
            binary,
            FfmpegArgs.mux(videoFile, audioFile, output),
            onProcess = { task.ffmpegProcess = it },
        ) { line ->
            val position = FfmpegArgs.parseOutTime(line)
            val now = System.currentTimeMillis()
            if (position != null && duration != null && duration > 0 && now - lastPublishAt >= 400) {
                lastPublishAt = now
                task.publish(
                    ProgressEvent(
                        status = TaskStatus.POSTPROCESSING,
                        percent = (80 + position / duration * 19).coerceIn(80.0, 99.0),
                        message = "Muxing video and audio (${selection.audio?.ext ?: ext})",
                    )
                )
            }
        }
        if (exit != 0 || !output.isFile || output.length() == 0L) {
            throw YtDlpException("ffmpeg could not mux the tracks into $ext")
        }
        videoFile.delete()
        audioFile.delete()
        return output
    }

    /** Move the finished file into the library with a readable, safe name. */
    private fun moveIntoLibrary(artifact: File, title: String, videoId: String?): File {
        val ext = MimeTypes.extensionOf(artifact.name)
        val target = MimeTypes.uniqueTarget(config.downloadDir, NewPipeMapper.fileName(title, ext, videoId))
        moveFile(artifact, target)
        return target
    }

    private fun startYtDlpDownload(request: DownloadRequest): Task {
        val task = tasks.create("download")
        task.job = scope.launch(Dispatchers.IO) {
            var workDir: File? = null
            try {
                if (!config.ensureDirectories()) throw YtDlpException("Download directory is not writable")
                task.publish(
                    ProgressEvent(status = TaskStatus.DOWNLOADING, percent = 0.0, message = "Resolving media")
                )
                val cookieFile = if (request.useCookies) cookies.fileOrNull() else null
                val ffmpegPath = yt.ffmpegPath ?: ffmpeg.binary()?.absolutePath
                workDir = File(config.downloadDir, ".prodl-${task.id}").apply { mkdirs() }
                val arguments = FormatSelector.buildArguments(request, workDir, cookieFile, ffmpegPath)
                task.processId = task.id

                var lastPublishAt = 0L
                val response = yt.download(request.url, arguments, task.id) { progress, eta, line ->
                    val sample = ProgressLine.parse(line)
                    val percent = if (progress >= 0f) progress.toDouble() else sample?.percent
                    val now = System.currentTimeMillis()
                    val important = sample?.filename != null || (percent != null && percent >= 100.0)
                    if (important || now - lastPublishAt >= 400) {
                        lastPublishAt = now
                        task.publish(
                            ProgressEvent(
                                status = if (percent != null && percent >= 100.0) {
                                    TaskStatus.POSTPROCESSING
                                } else {
                                    TaskStatus.DOWNLOADING
                                },
                                percent = percent,
                                downloadedBytes = sample?.downloadedBytes,
                                totalBytes = sample?.totalBytes,
                                speed = sample?.speed,
                                speedStr = sample?.speed?.let { ProgressLine.formatSpeed(it) },
                                eta = if (eta > 0) eta else sample?.eta,
                                filename = sample?.filename,
                                message = sample?.message ?: "Downloading",
                            )
                        )
                    }
                }

                if (task.cancelRequested.get()) {
                    task.markCanceled()
                    return@launch
                }
                if (response.exitCode != 0) {
                    throw YtDlpException(
                        cleanError(response.err.ifBlank { "yt-dlp exited with code ${response.exitCode}" })
                    )
                }
                task.publish(
                    ProgressEvent(status = TaskStatus.POSTPROCESSING, percent = 100.0, message = "Finishing file")
                )
                val artifact = pickArtifact(workDir, request.audioOnly)
                    ?: throw YtDlpException("yt-dlp finished but produced no media file")
                val target = moveYtDlpArtifact(artifact, workDir)
                task.finish(
                    FileRef(
                        filename = target.name,
                        filesize = target.length(),
                        downloadUrl = "/api/files/${task.id}",
                        mediaType = MimeTypes.guess(target.name),
                    ),
                    target,
                )
            } catch (canceled: YoutubeDL.CanceledException) {
                task.markCanceled()
            } catch (canceled: CancellationException) {
                if (!task.isDone) task.markCanceled()
            } catch (t: Throwable) {
                Log.w(TAG, "download ${task.id} failed", t)
                if (task.cancelRequested.get()) task.markCanceled() else task.fail(cleanError(t.message ?: t.toString()))
            } finally {
                workDir?.deleteRecursively()
            }
        }
        return task
    }

    fun startConvert(request: ConvertRequest, upload: File? = null): Task {
        val task = tasks.create("convert")
        task.job = scope.launch(Dispatchers.IO) {
            try {
                val input = upload ?: resolveInput(request)
                if (input == null || !input.isFile) {
                    throw IllegalArgumentException("Provide a task_id or a path inside the download directory")
                }
                val format = request.targetFormat.lowercase(Locale.US)
                if (format !in FfmpegArgs.allTargets) {
                    throw IllegalArgumentException("Unsupported target_format: ${request.targetFormat}")
                }
                config.ensureDirectories()
                val output = MimeTypes.uniqueTarget(config.downloadDir, FfmpegArgs.outputName(input, format))
                val binary = ffmpeg.binary() ?: throw YtDlpException("ffmpeg is not available on this device")
                val duration = ffmpeg.durationSeconds(input)
                task.publish(
                    ProgressEvent(status = TaskStatus.CONVERTING, percent = 0.0, message = "Converting to $format")
                )

                var lastPublishAt = 0L
                val arguments = FfmpegArgs.build(
                    input = input,
                    output = output,
                    format = format,
                    audioBitrate = request.audioBitrate,
                    videoCrf = request.videoCrf,
                    start = request.start,
                    end = request.end,
                )
                val exitCode = ffmpeg.run(binary, arguments, onProcess = { task.ffmpegProcess = it }) { line ->
                    val position = FfmpegArgs.parseOutTime(line)
                    if (position != null && duration != null && duration > 0) {
                        val now = System.currentTimeMillis()
                        if (now - lastPublishAt >= 400) {
                            lastPublishAt = now
                            task.publish(
                                ProgressEvent(
                                    status = TaskStatus.CONVERTING,
                                    percent = (position / duration * 100).coerceIn(0.0, 99.0),
                                    message = "Converting to $format",
                                )
                            )
                        }
                    }
                }

                if (task.cancelRequested.get()) {
                    output.delete()
                    task.markCanceled()
                    return@launch
                }
                if (exitCode != 0 || !output.isFile || output.length() == 0L) {
                    output.delete()
                    throw YtDlpException("ffmpeg failed with exit code $exitCode")
                }
                task.finish(
                    FileRef(
                        filename = output.name,
                        filesize = output.length(),
                        downloadUrl = "/api/files/${task.id}",
                        mediaType = MimeTypes.guess(output.name),
                    ),
                    output,
                )
            } catch (canceled: CancellationException) {
                if (!task.isDone) task.markCanceled()
            } catch (t: Throwable) {
                Log.w(TAG, "convert ${task.id} failed", t)
                if (task.cancelRequested.get()) task.markCanceled() else task.fail(cleanError(t.message ?: t.toString()))
            } finally {
                upload?.delete()
            }
        }
        return task
    }

    fun cancel(task: Task) {
        yt.cancel(task.id)
        tasks.cancel(task.id)
    }

    /** Run a NewPipe call, logging and swallowing failures so callers can fall back. */
    private inline fun <T> newPipeOrNull(block: () -> T): T? = runCatching(block)
        .onFailure { Log.w(TAG, "NewPipeExtractor call failed, falling back to yt-dlp", it) }
        .getOrNull()

    /** Resolve a convert input, refusing anything outside the app's own directories. */
    private fun resolveInput(request: ConvertRequest): File? {
        request.taskId?.let { id -> return tasks.get(id)?.filePath }
        val raw = request.path ?: return null
        val candidate = File(raw)
        val allowedRoots = listOf(config.downloadDir, config.uploadsDir).map { it.canonicalFile }
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        return if (allowedRoots.any { canonical.path.startsWith(it.path + File.separator) }) canonical else null
    }

    private fun pickArtifact(workDir: File, audioOnly: Boolean): File? {
        val files = workDir.listFiles()?.filter { it.isFile } ?: return null
        val media = files.filter { file ->
            val ext = MimeTypes.extensionOf(file.name)
            ext.isNotEmpty() && ext !in setOf("part", "ytdl", "temp", "vtt", "srt", "ass", "jpg", "png", "webp")
        }
        val preferred = when {
            audioOnly -> media.filter { MimeTypes.isAudio(it.name) }
            else -> media.filter { MimeTypes.isVideo(it.name) }
        }
        return (preferred.ifEmpty { media }).maxByOrNull { it.length() }
    }

    /** Move the artifact (plus sidecar subtitles) out of the work dir into the library. */
    private fun moveYtDlpArtifact(artifact: File, workDir: File): File {
        val base = artifact.nameWithoutExtension
        val target = MimeTypes.uniqueTarget(config.downloadDir, MimeTypes.safeName(artifact.name))
        moveFile(artifact, target)
        workDir.listFiles()?.forEach { sidecar ->
            if (sidecar.isFile && sidecar.nameWithoutExtension.startsWith(base)) {
                val sideTarget = MimeTypes.uniqueTarget(config.downloadDir, MimeTypes.safeName(sidecar.name))
                moveFile(sidecar, sideTarget)
            }
        }
        return target
    }

    private fun moveFile(source: File, target: File) {
        if (source.renameTo(target)) return
        runCatching {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
    }

    companion object {
        private const val TAG = "ProDL.Media"
    }
}
