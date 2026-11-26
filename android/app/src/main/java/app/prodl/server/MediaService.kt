package app.prodl.server

import android.content.Context
import android.util.Log
import app.prodl.core.CookieStore
import app.prodl.core.DownloadRequest
import app.prodl.core.ConvertRequest
import app.prodl.core.FileRef
import app.prodl.core.FfmpegArgs
import app.prodl.core.FfmpegRunner
import app.prodl.core.FormatSelector
import app.prodl.core.InfoResponse
import app.prodl.core.MimeTypes
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

/**
 * Everything that actually talks to yt-dlp/ffmpeg. Routes stay thin and only
 * validate input, mirroring the Python backend's service layer.
 */
class MediaService(
    private val context: Context,
    private val config: ServerConfig,
    private val tasks: TaskManager,
    private val yt: YtDlp,
    private val ffmpeg: FfmpegRunner,
    private val cookies: CookieStore,
    private val sponsor: SponsorBlockClient,
    private val scope: CoroutineScope,
) {
    suspend fun search(query: String, limit: Int): SearchResponse {
        val effectiveLimit = limit.coerceIn(1, config.searchLimitMax)
        val json = yt.dumpJson(
            "ytsearch$effectiveLimit:$query",
            listOf("--flat-playlist" to null),
        )
        return YtDlpJson.search(query, effectiveLimit, json)
    }

    suspend fun info(url: String, withSponsorBlock: Boolean, categories: List<String>?): InfoResponse {
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

    fun startDownload(request: DownloadRequest): Task {
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
                val target = moveIntoLibrary(artifact, workDir)
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
                val format = request.targetFormat.lowercase()
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
    private fun moveIntoLibrary(artifact: File, workDir: File): File {
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
