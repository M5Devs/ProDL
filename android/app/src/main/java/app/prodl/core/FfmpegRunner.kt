package app.prodl.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Locates and runs the ffmpeg binary that youtubedl-android extracts into the
 * app sandbox, and builds the argument lists for conversions.
 */
class FfmpegRunner(context: Context) {

    private val appContext: Context = context.applicationContext ?: context

    @Volatile
    private var cached: File? = null

    /** Caches hits only: on first launch the binary appears after extraction. */
    fun binary(): File? {
        cached?.let { if (it.isFile) return it }
        return synchronized(this) {
            if (cached?.isFile != true) cached = resolve("ffmpeg")
            cached
        }
    }

    fun probeBinary(): File? = resolve("ffprobe")

    @Volatile
    private var cachedVersion: String? = null

    /** First line of `ffmpeg -version`, cached: the health check calls this often. */
    fun version(): String? {
        cachedVersion?.let { return it }
        val file = binary() ?: return null
        return synchronized(this) {
            cachedVersion ?: runCatching {
                val process = ProcessBuilder(file.absolutePath, "-version")
                    .redirectErrorStream(true)
                    .start()
                val line = process.inputStream.bufferedReader().use { it.readLine() }
                process.waitFor()
                line?.trim()?.take(120)?.ifEmpty { null }
            }.getOrNull()?.also { cachedVersion = it }
        }
    }

    private fun resolve(name: String): File? {
        val root = File(appContext.filesDir, "youtubedl-android")
        val packages = File(root, "packages/ffmpeg")
        val nativeDir = File(appContext.applicationInfo.nativeLibraryDir)

        val candidates = mutableListOf<File>()
        candidates += File(packages, name)
        // FFmpeg.init() unpacks libffmpeg.zip.so into packages/ffmpeg/<version>/.
        packages.listFiles()?.sortedByDescending { it.name }?.forEach { entry ->
            if (entry.isFile && entry.name.startsWith(name)) candidates += entry
        }
        if (packages.isDirectory) {
            packages.walkTopDown().maxDepth(2)
                .filter { it.isFile && it.name == name }
                .forEach { candidates += it }
        }
        candidates += File(nativeDir, "lib$name.so")
        candidates += File(nativeDir, name)
        candidates += File(root, name)

        val existing = candidates.firstOrNull { it.isFile && it.length() > 0 }
        existing?.let { file ->
            if (!file.canExecute()) runCatching { file.setExecutable(true) }
        }
        return existing
    }

    /** Run ffmpeg/ffprobe, streaming its combined output to [onLine]. Returns the exit code. */
    suspend fun run(
        binary: File,
        arguments: List<String>,
        onProcess: ((Process) -> Unit)? = null,
        onLine: (String) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(listOf(binary.absolutePath) + arguments)
            .redirectErrorStream(true)
            .start()
        onProcess?.invoke(process)
        runCatching { process.outputStream.close() }
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line -> runCatching { onLine(line) } }
        }
        process.waitFor()
    }

    suspend fun durationSeconds(file: File): Double? {
        val probe = probeBinary() ?: return null
        val output = StringBuilder()
        val code = run(
            probe,
            listOf("-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", file.absolutePath),
        ) { line -> output.appendLine(line) }
        if (code != 0) return null
        return output.toString().trim().lineSequence().lastOrNull()?.toDoubleOrNull()
    }
}

/** Pure argument builder, unit tested. */
object FfmpegArgs {
    val audioTargets = setOf("mp3", "m4a", "opus", "flac", "wav")
    val videoTargets = setOf("mp4", "webm", "gif")
    val allTargets = audioTargets + videoTargets

    fun build(
        input: File,
        output: File,
        format: String,
        audioBitrate: String = "192k",
        videoCrf: Int = 23,
        start: Double? = null,
        end: Double? = null,
    ): List<String> {
        val target = format.lowercase()
        require(target in allTargets) { "Unsupported target format: $format" }
        if (start != null) require(start >= 0.0) { "start must be >= 0" }
        if (start != null && end != null) require(end > start) { "end must be greater than start" }

        val args = mutableListOf(
            "-hide_banner",
            "-y",
            "-nostdin",
            "-progress", "pipe:1",
            "-nostats",
        )
        if (start != null) args += listOf("-ss", seconds(start))
        args += listOf("-i", input.absolutePath)
        if (start != null && end != null) args += listOf("-t", seconds(end - start))

        when (target) {
            "mp3" -> args += listOf("-vn", "-c:a", "libmp3lame", "-b:a", audioBitrate)
            "m4a" -> args += listOf("-vn", "-c:a", "aac", "-b:a", audioBitrate)
            "opus" -> args += listOf("-vn", "-c:a", "libopus", "-b:a", audioBitrate)
            "flac" -> args += listOf("-vn", "-c:a", "flac")
            "wav" -> args += listOf("-vn", "-c:a", "pcm_s16le")
            "mp4" -> args += listOf(
                "-c:v", "libx264", "-crf", videoCrf.toString(), "-preset", "veryfast",
                "-c:a", "aac", "-b:a", audioBitrate, "-movflags", "+faststart",
            )
            "webm" -> args += listOf(
                "-c:v", "libvpx-vp9", "-crf", videoCrf.toString(), "-b:v", "0",
                "-c:a", "libopus",
            )
            "gif" -> args += listOf("-vf", "fps=15,scale=480:-1:flags=lanczos", "-loop", "0")
        }
        args += output.absolutePath
        return args
    }

    /**
     * Join a video-only and an audio-only stream without re-encoding. The
     * container is chosen so the copied codecs stay legal (see [muxExtension]).
     */
    fun mux(video: File, audio: File, output: File): List<String> = listOf(
        "-hide_banner",
        "-y",
        "-nostdin",
        "-progress", "pipe:1",
        "-nostats",
        "-i", video.absolutePath,
        "-i", audio.absolutePath,
        "-map", "0:v:0",
        "-map", "1:a:0",
        "-c", "copy",
        "-movflags", "+faststart",
        output.absolutePath,
    )

    /**
     * Container for a `-c copy` mux. MP4 only accepts a subset of codecs, MKV
     * accepts everything, so anything that is not a known-good pair lands there
     * instead of failing at the end of a long download.
     */
    fun muxExtension(videoExt: String, audioExt: String): String {
        val video = videoExt.lowercase(Locale.US)
        val audio = audioExt.lowercase(Locale.US)
        val mp4Audio = setOf("m4a", "aac", "mp4")
        return when {
            video == "mp4" && audio in mp4Audio -> "mp4"
            video == "webm" && audio in setOf("webm", "opus", "ogg") -> "webm"
            video == "mp4" && audio == "opus" -> "mkv"
            video == "webm" && audio in mp4Audio -> "mkv"
            else -> "mkv"
        }
    }

    fun isMp4Audio(ext: String): Boolean = ext.lowercase(Locale.US) in setOf("m4a", "aac", "mp4")

    fun outputName(input: File, format: String): String =
        "${input.nameWithoutExtension}.${format.lowercase()}"

    fun seconds(value: Double): String = String.format(Locale.US, "%.3f", value)

    /** `-progress pipe:1` emits `out_time=HH:MM:SS.micro`; return the position in seconds. */
    fun parseOutTime(line: String): Double? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("out_time=")) return null
        val value = trimmed.removePrefix("out_time=").trim()
        if (value == "N/A") return null
        val parts = value.split(':')
        if (parts.size != 3) return null
        val hours = parts[0].toDoubleOrNull() ?: return null
        val minutes = parts[1].toDoubleOrNull() ?: return null
        val seconds = parts[2].toDoubleOrNull() ?: return null
        return hours * 3600 + minutes * 60 + seconds
    }
}
