package app.prodl

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import app.prodl.bridge.NativeBridge
import app.prodl.core.MediaLibrary
import app.prodl.service.ServerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Hosts the shared SPA (the exact same files the web build serves) in a WebView
 * pointed at the loopback API server.
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var loading: LinearLayout
    private lateinit var loadingText: TextView
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingExportTaskId: String? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) toast(getString(R.string.permission_needed))
        }

    private val legacyStoragePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val taskId = pendingExportTaskId
            pendingExportTaskId = null
            if (granted && taskId != null) {
                saveTaskToDownloads(taskId)
            } else if (!granted) {
                toast(getString(R.string.save_failed))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::webView.isInitialized && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        ServerService.start(this)
        requestNotificationPermission()
        waitForServerThenLoad()
    }

    // ── UI ──────────────────────────────────────────────────────────────────

    private fun buildUi() {
        val root = FrameLayout(this)
        webView = WebView(this).apply { visibility = View.GONE }
        configureWebView()
        root.addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        loading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.prodl_background))
            addView(ProgressBar(this@MainActivity))
            loadingText = TextView(this@MainActivity).apply {
                text = getString(R.string.starting_server)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.prodl_text))
                gravity = Gravity.CENTER
                setPadding(48, 32, 48, 0)
            }
            addView(loadingText)
        }
        root.addView(loading, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    @Suppress("DEPRECATION")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            // The SPA is loaded from file:///android_asset and talks to
            // http://127.0.0.1:<port>, which Chrome treats as a foreign origin.
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            userAgentString = "$userAgentString ProDLAndroid/${BuildConfig.VERSION_NAME}"
        }
        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.prodl_background))
        webView.addJavascriptInterface(NativeBridge(this), "ProDLNative")
        webView.webViewClient = ProDLWebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d(TAG, "web: ${consoleMessage.message()} (${consoleMessage.lineNumber()})")
                return true
            }
        }
        webView.setDownloadListener { url, _, _, _, _ -> handleDownload(url) }
    }

    private fun waitForServerThenLoad() {
        scope.launch {
            val deadline = System.currentTimeMillis() + SERVER_START_TIMEOUT_MS
            var port = 0
            while (System.currentTimeMillis() < deadline) {
                port = serverPort()
                if (port != 0) break
                delay(150)
            }
            if (port == 0) {
                loadingText.text = getString(R.string.server_failed)
                return@launch
            }
            loadSpa()
        }
    }

    /**
     * Loads the shared SPA from the APK with the API base injected ahead of its
     * own scripts. `loadDataWithBaseURL` guarantees the bootstrap runs first,
     * while relative assets still resolve against `file:///android_asset/frontend/`.
     */
    private fun loadSpa() {
        val html = injectedHtml()
        if (html == null) {
            loadingText.text = getString(R.string.server_failed)
            return
        }
        webView.loadDataWithBaseURL(ASSET_BASE, html, "text/html", "utf-8", null)
    }

    private fun injectedHtml(): String? = runCatching {
        val raw = assets.open("frontend/index.html").bufferedReader().use { it.readText() }
        val bootstrap = buildString {
            append("<script>")
            append("window.__PRODL_API_BASE__=").append(JSONObject.quote(apiBase())).append(";")
            append("window.__PRODL_PLATFORM__='android';")
            append("window.__PRODL_VERSION__=").append(JSONObject.quote(BuildConfig.VERSION_NAME)).append(";")
            append("</script>")
        }
        if (raw.contains("</head>")) raw.replaceFirst("</head>", "$bootstrap</head>") else bootstrap + raw
    }.getOrNull()

    private inner class ProDLWebViewClient : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            loading.visibility = View.GONE
            webView.visibility = View.VISIBLE
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            super.onReceivedError(view, request, error)
            if (request.isForMainFrame) {
                loadingText.text = getString(R.string.server_failed)
                loading.visibility = View.VISIBLE
            }
        }
    }

    // ── Downloads ───────────────────────────────────────────────────────────

    private fun handleDownload(url: String) {
        val taskId = Regex("/api/files/([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)
        if (taskId != null) {
            saveTaskToDownloads(taskId)
        } else {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
        }
    }

    fun saveTaskToDownloads(taskId: String?) {
        if (taskId.isNullOrBlank()) {
            toast(getString(R.string.save_failed))
            return
        }
        val file = app().server?.tasks?.get(taskId)?.filePath
        if (file == null) {
            toast(getString(R.string.save_failed))
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingExportTaskId = taskId
            legacyStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        scope.launch {
            val uri = MediaLibrary.export(this@MainActivity, file)
            toast(if (uri != null) getString(R.string.saved_to_downloads) else getString(R.string.save_failed))
        }
    }

    // ── Bridge helpers ──────────────────────────────────────────────────────

    fun serverPort(): Int = app().server?.port ?: 0

    fun apiBase(): String = "http://127.0.0.1:${serverPort()}"

    fun openCookieLogin() {
        startActivity(Intent(this, CookieLoginActivity::class.java))
    }

    fun notificationPermissionState(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return "not_required"
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return if (granted) "granted" else "denied"
    }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (notificationPermissionState() == "granted") return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun app(): ProDLApplication = application as ProDLApplication

    // ── Lifecycle ───────────────────────────────────────────────────────────

    override fun onDestroy() {
        scope.cancel()
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ProDL.Main"
        private const val ASSET_BASE = "file:///android_asset/frontend/"
        private const val SERVER_START_TIMEOUT_MS = 60_000L
        private const val MATCH_PARENT = FrameLayout.LayoutParams.MATCH_PARENT
    }
}
