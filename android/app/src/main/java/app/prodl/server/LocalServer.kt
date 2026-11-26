package app.prodl.server

import android.content.Context
import android.os.Build
import android.util.Log
import app.prodl.BuildConfig
import app.prodl.core.ApiIndex
import app.prodl.core.AppJson
import app.prodl.core.ComponentStatus
import app.prodl.core.CookieStore
import app.prodl.core.CookieFormatException
import app.prodl.core.ErrorResponse
import app.prodl.core.MediaPipeline
import app.prodl.core.NewPipeEngine
import app.prodl.core.HealthResponse
import app.prodl.core.ServerConfig
import app.prodl.core.SponsorBlockClient
import app.prodl.core.YtDlp
import app.prodl.core.YtDlpException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The on-device API server. Bound to 127.0.0.1 on an ephemeral port so nothing
 * outside the phone can reach it.
 */
class LocalServer(
    private val context: Context,
    val config: ServerConfig = ServerConfig.default(context),
) {
    val tasks = TaskManager(config.maxTasks, config.taskTtlSeconds)
    val cookies = CookieStore(config.cookiesFile)
    private val yt = YtDlp(context)
    val newpipe = NewPipeEngine(context)
    private val pipeline = MediaPipeline(context)
    private val sponsor = SponsorBlockClient(config.sponsorBlockApi, config.sponsorBlockCategories)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val media = MediaService(config, tasks, yt, newpipe, pipeline, cookies, sponsor, scope)

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val startedAt = System.currentTimeMillis()

    @Volatile
    var port: Int = 0
        private set

    val baseUrl: String get() = "http://127.0.0.1:$port"

    fun uptimeSeconds(): Double = (System.currentTimeMillis() - startedAt) / 1000.0

    /** Kick off engine start-up early so the first request is not slow. */
    fun prepare() {
        config.ensureDirectories()
        // NewPipeExtractor needs no extraction: it is ready in milliseconds and
        // is what makes YouTube work without Python.
        newpipe.initialiseAsync(scope)
        yt.initAsync(scope)
    }

    suspend fun start(): Int {
        if (port != 0) return port
        prepare()
        port = try {
            startEngine(config.preferredPort)
        } catch (bindFailure: Throwable) {
            if (config.preferredPort == 0) throw bindFailure
            Log.w(TAG, "port ${config.preferredPort} unavailable, falling back to an ephemeral port")
            startEngine(0)
        }
        return port
    }

    /** Stops the HTTP engine only; the app-wide scope keeps running downloads alive. */
    fun stop() {
        runCatching { server?.stop(500, 1500) }
        server = null
        port = 0
    }

    private suspend fun startEngine(bindPort: Int): Int {
        val embedded = embeddedServer(CIO, host = LOOPBACK, port = bindPort) { module() }
        embedded.start(wait = false)
        server = embedded
        return embedded.engine.resolvedConnectors().first().port
    }

    fun health(): HealthResponse {
        val version = yt.version()
        val initError = yt.initializationError
        val ytReady = yt.isReady
        val npReady = newpipe.isReady
        val writable = config.downloadDir.isDirectory && config.downloadDir.canWrite()
        // Search and info need one working engine, not both: NewPipeExtractor
        // covers YouTube, yt-dlp covers everything else.
        val canExtract = npReady || ytReady
        return HealthResponse(
            // "ok" = an extraction engine, a media pipeline and a writable dir.
            status = if (canExtract && pipeline.available && writable) "ok" else "degraded",
            apiVersion = config.apiVersion,
            version = BuildConfig.VERSION_NAME,
            uptimeSeconds = uptimeSeconds(),
            platform = "android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            python = "n/a (kotlin ${KotlinVersion.CURRENT})",
            dataDir = config.dataDir.absolutePath,
            downloadDir = config.downloadDir.absolutePath,
            downloadDirWritable = writable,
            ytDlp = ComponentStatus(
                available = ytReady,
                version = version,
                note = when {
                    ytReady -> "bundled · fallback for non-YouTube sites"
                    initError != null -> initError
                    else -> "initialising: ${yt.step}"
                },
                state = when {
                    ytReady -> "ready"
                    initError != null -> "failed"
                    else -> "initialising"
                },
            ),
            newpipe = ComponentStatus(
                available = npReady,
                version = BuildConfig.NEWPIPE_VERSION,
                note = when {
                    npReady -> "primary YouTube engine · no Python required"
                    newpipe.error != null -> newpipe.error
                    else -> "starting"
                },
                state = when {
                    npReady -> "ready"
                    newpipe.error != null -> "failed"
                    else -> "initialising"
                },
            ),
            // No bundled binary any more: the platform codecs do the work, so
            // this cannot fail with CANNOT LINK EXECUTABLE or hang on unpacking.
            transcoder = ComponentStatus(
                available = pipeline.available,
                version = pipeline.version,
                note = pipeline.description,
                state = "ready",
            ),
            jsRuntime = ComponentStatus(
                available = true,
                version = "quickjs (bundled)",
                note = "used by yt-dlp to solve YouTube challenges on-device",
            ),
            cookies = cookies.status(),
            features = mapOf(
                "search" to canExtract,
                "info" to canExtract,
                "download" to canExtract,
                "newpipe" to npReady,
                "yt_dlp" to ytReady,
                "sponsorblock" to true,
                "subtitles" to true,
                "playlist" to true,
                "convert" to pipeline.available,
                "mp3" to pipeline.available,
                "mux" to pipeline.available,
                "trim" to pipeline.available,
                "websocket" to true,
                "sse" to true,
                "native_playback" to true,
                "cookie_login" to true,
            ),
        )
    }

    fun index(): ApiIndex = ApiIndex(
        name = "ProDL local server (Android)",
        version = BuildConfig.VERSION_NAME,
        apiVersion = config.apiVersion,
        docs = DOCS_URL,
        openapi = "/api/openapi.json",
        endpoints = ENDPOINTS,
    )

    /** Minimal OpenAPI stub: the authoritative schema lives in API_SPEC.md. */
    fun openApi(): JsonObject = buildJsonObject {
        put("openapi", "3.1.0")
        putJsonObject("info") {
            put("title", "ProDL local API")
            put("version", config.apiVersion)
            put(
                "description",
                "On-device implementation of $DOCS_URL. Paths mirror the Python backend; " +
                    "the Android build serves the same static SPA from the APK.",
            )
        }
        putJsonArray("servers") { addJsonObject { put("url", "http://127.0.0.1:$port") } }
        putJsonObject("paths") {
            ENDPOINTS.keys.forEach { path ->
                val route = path.substringAfter(' ')
                putJsonObject(route) {
                    putJsonObject(path.substringBefore(' ').lowercase()) {
                        put("summary", ENDPOINTS[path])
                        putJsonObject("responses") {
                            putJsonObject("200") { put("description", "OK") }
                        }
                    }
                }
            }
        }
    }

    private fun Application.module() {
        install(CORS) {
            // The SPA runs from file:// (origin "null") inside the WebView, so
            // every request is cross-origin. Loopback-only binding is the real
            // access control here.
            anyHost()
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Delete)
            allowMethod(HttpMethod.Options)
            allowMethod(HttpMethod.Head)
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Accept)
            allowHeader(HttpHeaders.Authorization)
            allowHeader(HttpHeaders.Range)
            allowNonSimpleContentTypes = true
            exposeHeader(HttpHeaders.ContentDisposition)
            exposeHeader(HttpHeaders.ContentLength)
            maxAgeInSeconds = 600
        }
        install(ContentNegotiation) { json(AppJson) }
        // Range requests: Media3 seeks in served files, downloads can resume.
        install(PartialContent)
        install(WebSockets) {
            pingPeriodMillis = 20_000L
            timeoutMillis = 40_000L
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }
        install(StatusPages) {
            exception<ApiNotFound> { call, cause ->
                call.respond(HttpStatusCode.NotFound, ErrorResponse(cause.message ?: "Not found"))
            }
            exception<ApiBadRequest> { call, cause ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "Bad request"))
            }
            exception<ApiUnprocessable> { call, cause ->
                call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse(cause.message ?: "Invalid input"))
            }
            exception<ApiTooLarge> { call, cause ->
                call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse(cause.message ?: "Payload too large"))
            }
            exception<CookieFormatException> { call, cause ->
                call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse(cause.message ?: "Invalid cookies"))
            }
            exception<YtDlpException> { call, cause ->
                call.respond(HttpStatusCode.BadGateway, ErrorResponse(cause.message ?: "yt-dlp failed"))
            }
            exception<IllegalArgumentException> { call, cause ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "Bad request"))
            }
            exception<ApiUpstream> { call, cause ->
                call.respond(HttpStatusCode.BadGateway, ErrorResponse(cause.message ?: "Upstream failed"))
            }
            exception<Throwable> { call, cause ->
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse(cause.message ?: cause::class.java.simpleName),
                )
            }
            status(HttpStatusCode.NotFound) { call, status ->
                call.respond(status, ErrorResponse("Not found"))
            }
        }
        routing {
            apiRoutes(this@LocalServer)
        }
    }

    companion object {
        private const val TAG = "ProDL.Server"
        private const val LOOPBACK = "127.0.0.1"
        private const val DOCS_URL = "https://github.com/M5Devs/ProDL/blob/main/API_SPEC.md"

        private val ENDPOINTS = linkedMapOf(
            "GET /api" to "API index",
            "GET /api/health" to "Server status, engines and media-pipeline availability",
            "GET /api/search?q=&limit=" to "YouTube search without an API key",
            "GET /api/info?url=&sponsorblock=" to "Metadata, formats, subtitles, SponsorBlock",
            "POST /api/download" to "Start a download task",
            "GET /api/tasks/{task_id}" to "Task snapshot",
            "POST /api/tasks/{task_id}/cancel" to "Cancel a task",
            "WS /api/ws/progress/{task_id}" to "Live progress stream",
            "GET /api/progress/{task_id}" to "Live progress stream (SSE)",
            "POST /api/convert" to "Convert or trim an existing file",
            "POST /api/convert/upload" to "Upload and convert a local file",
            "GET /api/files/{task_id}" to "Download the finished file",
            "DELETE /api/files/{task_id}" to "Delete the finished file",
            "GET /api/cookies" to "Cookie store status",
            "POST /api/cookies" to "Save Netscape cookies",
            "POST /api/cookies/upload" to "Upload a Netscape cookie file",
            "DELETE /api/cookies" to "Clear the cookie store",
        )
    }
}
