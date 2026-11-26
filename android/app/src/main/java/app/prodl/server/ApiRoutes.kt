package app.prodl.server

import app.prodl.core.AppJson
import app.prodl.core.CookiesRequest
import app.prodl.core.CookiesStatus
import app.prodl.core.ConvertRequest
import app.prodl.core.DownloadRequest
import app.prodl.core.ErrorResponse
import app.prodl.core.ProgressEvent
import app.prodl.core.TaskAccepted
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.Writer
import java.net.URLEncoder
import java.util.UUID

/** Signals the end of a progress stream without treating it as an error. */
private object StreamFinished : Throwable("stream finished")

fun Route.apiRoutes(server: LocalServer) {
    val config = server.config
    val tasks = server.tasks
    val media = server.media

    get("/") { call.respond(server.index()) }
    get("/api") { call.respond(server.index()) }
    get("/api/openapi.json") {
        call.respondText(
            AppJson.encodeToString(JsonObject.serializer(), server.openApi()),
            ContentType.Application.Json,
        )
    }

    route("/api") {
        get("/health") { call.respond(server.health()) }

        get("/search") {
            val query = call.request.queryParameters["q"]?.trim().orEmpty()
            if (query.isEmpty()) throw ApiBadRequest("q is required")
            if (query.length > 200) throw ApiBadRequest("q must be 200 characters or fewer")
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: config.searchLimit
            call.respond(media.search(query, limit))
        }

        get("/info") {
            val url = requireUrl(call.request.queryParameters["url"])
            val sponsorblock = parseFlag(call.request.queryParameters["sponsorblock"])
            val categories = call.request.queryParameters["categories"]
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.ifEmpty { null }
            call.respond(media.info(url, sponsorblock, categories))
        }

        post("/download") {
            val request = call.receive<DownloadRequest>()
            requireUrl(request.url)
            val task = media.startDownload(request)
            call.respond(HttpStatusCode.Accepted, accepted(task))
        }

        get("/tasks/{taskId}") { call.respond(taskOf(tasks, call).snapshot()) }

        post("/tasks/{taskId}/cancel") {
            val task = taskOf(tasks, call)
            media.cancel(task)
            call.respond(task.snapshot())
        }

        get("/progress/{taskId}") {
            val task = taskOf(tasks, call)
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.response.header("X-Accel-Buffering", "no")
            call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                var lastSeq = 0L
                task.lastEvent?.let { event ->
                    lastSeq = event.seq ?: 0
                    writeEvent(event)
                    flush()
                }
                if (task.isDone) return@respondTextWriter
                try {
                    task.events.collect { event ->
                        val seq = event.seq ?: 0
                        if (seq <= lastSeq) return@collect
                        lastSeq = seq
                        writeEvent(event)
                        flush()
                        if (event.status.isTerminal) throw StreamFinished
                    }
                } catch (finished: StreamFinished) {
                    // Terminal event delivered; closing the stream is normal.
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (disconnected: IOException) {
                    // Client went away.
                }
            }
        }

        webSocket("/ws/progress/{taskId}") {
            val task = tasks.get(call.parameters["taskId"].orEmpty())
            if (task == null) {
                send(Frame.Text(encode(ErrorResponse("Unknown task_id"))))
                close(CloseReason(CloseReason.Codes.NORMAL, "unknown task"))
                return@webSocket
            }
            var lastSeq = 0L
            task.lastEvent?.let { event ->
                lastSeq = event.seq ?: 0
                send(Frame.Text(encode(event)))
            }
            if (task.isDone) {
                close(CloseReason(CloseReason.Codes.NORMAL, "finished"))
                return@webSocket
            }
            val pinger = launch {
                while (isActive) {
                    delay(PING_INTERVAL_MS)
                    send(Frame.Text("""{"type":"ping","timestamp":${nowSeconds()}}"""))
                }
            }
            try {
                task.events.collect { event ->
                    val seq = event.seq ?: 0
                    if (seq <= lastSeq) return@collect
                    lastSeq = seq
                    send(Frame.Text(encode(event)))
                    if (event.status.isTerminal) throw StreamFinished
                }
            } catch (finished: StreamFinished) {
                runCatching { close(CloseReason(CloseReason.Codes.NORMAL, "finished")) }
            } catch (cancelled: CancellationException) {
                // Session closed.
            } catch (t: Throwable) {
                runCatching { close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "stream error")) }
            } finally {
                pinger.cancel()
            }
        }

        get("/files/{taskId}") {
            val task = taskOf(tasks, call)
            val file = task.filePath?.takeIf { it.isFile }
                ?: throw ApiNotFound("No finished file for this task")
            call.response.header(HttpHeaders.ContentDisposition, contentDisposition(file.name))
            call.respondFile(file)
        }

        delete("/files/{taskId}") {
            val task = taskOf(tasks, call)
            task.filePath?.delete()
            task.filePath = null
            task.file = null
            call.respond(HttpStatusCode.NoContent)
        }

        post("/convert") {
            val request = call.receive<ConvertRequest>()
            if (request.taskId.isNullOrBlank() && request.path.isNullOrBlank()) {
                throw ApiBadRequest("Provide task_id or path")
            }
            call.respond(HttpStatusCode.Accepted, accepted(media.startConvert(request)))
        }

        post("/convert/upload") {
            var upload: File? = null
            var format = "mp3"
            var bitrate = "192k"
            var crf = 23
            var start: Double? = null
            var end: Double? = null
            try {
                call.receiveMultipart(formFieldLimit = FORM_FIELD_LIMIT).forEachPart { part ->
                    when (part) {
                        is PartData.FileItem -> {
                            val original = part.originalFileName ?: "upload"
                            val target = File(
                                config.uploadsDir,
                                "upload-${UUID.randomUUID().toString().take(12)}${extensionSuffix(original)}",
                            )
                            target.parentFile?.mkdirs()
                            val written = part.provider().copyTo(target, config.maxUploadBytes)
                            if (written == 0L) {
                                target.delete()
                                throw ApiBadRequest("Uploaded file is empty")
                            }
                            upload = target
                        }
                        is PartData.FormItem -> when (part.name) {
                            "target_format" -> format = part.value
                            "audio_bitrate" -> bitrate = part.value
                            "video_crf" -> crf = part.value.toIntOrNull() ?: 23
                            "start" -> start = part.value.toDoubleOrNull()
                            "end" -> end = part.value.toDoubleOrNull()
                        }
                        else -> Unit
                    }
                    part.release()
                }
                val file = upload ?: throw ApiBadRequest("file is required")
                val request = ConvertRequest(
                    targetFormat = format,
                    audioBitrate = bitrate,
                    videoCrf = crf,
                    start = start,
                    end = end,
                )
                call.respond(HttpStatusCode.Accepted, accepted(media.startConvert(request, file)))
            } catch (t: Throwable) {
                upload?.delete()
                throw t
            }
        }

        route("/cookies") {
            get { call.respond(server.cookies.status()) }

            post {
                val content = if (call.request.contentType().match(ContentType.Application.Json)) {
                    call.receive<CookiesRequest>().content
                } else {
                    call.receiveText()
                }
                if (content.toByteArray().size > config.maxCookieBytes) {
                    throw ApiTooLarge("Cookie file exceeds ${config.maxCookieBytes / (1024 * 1024)} MiB")
                }
                call.respond(server.cookies.save(content))
            }

            post("/upload") {
                var saved: CookiesStatus? = null
                call.receiveMultipart(formFieldLimit = FORM_FIELD_LIMIT).forEachPart { part ->
                    if (part is PartData.FileItem) {
                        val text = part.provider().readTextLimited(config.maxCookieBytes)
                        saved = server.cookies.save(text)
                    }
                    part.release()
                }
                call.respond(saved ?: throw ApiBadRequest("cookies file is required"))
            }

            delete { call.respond(server.cookies.clear()) }
        }
    }
}

private const val PING_INTERVAL_MS = 20_000L
private const val FORM_FIELD_LIMIT = 1L * 1024 * 1024

private fun encode(event: ProgressEvent): String =
    AppJson.encodeToString(ProgressEvent.serializer(), event)

private fun encode(error: ErrorResponse): String =
    AppJson.encodeToString(ErrorResponse.serializer(), error)

private fun Writer.writeEvent(event: ProgressEvent) {
    write("event: progress\n")
    write("data: ${encode(event)}\n\n")
}

private fun requireUrl(raw: String?): String {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) throw ApiBadRequest("url is required")
    if (value.length < 4) throw ApiBadRequest("url is too short")
    if (value.length > 2048) throw ApiBadRequest("url is too long")
    return value
}

private fun parseFlag(raw: String?): Boolean =
    raw != null && raw.lowercase() in setOf("1", "true", "yes", "on")

private fun taskOf(tasks: TaskManager, call: ApplicationCall): Task {
    val id = call.parameters["taskId"].orEmpty()
    return tasks.get(id) ?: throw ApiNotFound("Unknown task_id: $id")
}

private fun accepted(task: Task): TaskAccepted = TaskAccepted(
    taskId = task.id,
    kind = task.kind,
    status = task.status,
    createdAt = task.createdAt,
    websocketUrl = "/api/ws/progress/${task.id}",
    sseUrl = "/api/progress/${task.id}",
    taskUrl = "/api/tasks/${task.id}",
    fileUrl = "/api/files/${task.id}",
)

private fun contentDisposition(filename: String): String {
    val ascii = filename.map { if (it.code in 32..126) it else '_' }.joinToString("")
        .replace("\"", "_")
    val encoded = URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
    return "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded"
}

private fun extensionSuffix(name: String): String {
    val ext = name.substringAfterLast('.', "").filter { it.isLetterOrDigit() }.lowercase()
    return if (ext.isEmpty()) "" else ".$ext"
}

private suspend fun ByteReadChannel.copyTo(target: File, limit: Long): Long {
    var total = 0L
    target.outputStream().use { out ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = readAvailable(buffer, 0, buffer.size)
            if (read == -1) break
            if (read == 0) {
                yield()
                continue
            }
            total += read
            if (total > limit) {
                throw ApiTooLarge("Upload exceeds ${limit / (1024 * 1024)} MiB")
            }
            out.write(buffer, 0, read)
        }
    }
    return total
}

private suspend fun ByteReadChannel.readTextLimited(limit: Long): String {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
        val read = readAvailable(buffer, 0, buffer.size)
        if (read == -1) break
        if (read == 0) {
            yield()
            continue
        }
        total += read
        if (total > limit) {
            throw ApiTooLarge("Cookie file exceeds ${limit / (1024 * 1024)} MiB")
        }
        out.write(buffer, 0, read)
    }
    return out.toString("UTF-8")
}
