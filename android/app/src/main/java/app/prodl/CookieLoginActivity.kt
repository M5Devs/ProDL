package app.prodl

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import app.prodl.core.CookieStore
import app.prodl.core.NetscapeCookies
import java.io.File

/**
 * In-app sign-in. The WebView talks to Google directly; when the user taps
 * "Save & Link Cookies" the session cookies are converted to Netscape format
 * and written to the app's private store. Nothing is uploaded anywhere.
 */
class CookieLoginActivity : ComponentActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
        }
        val progress = ProgressBar(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(48, 48)
        }
        val save = Button(this).apply {
            text = getString(R.string.save_cookies)
            setOnClickListener { persistCookies() }
        }
        val cancel = Button(this).apply {
            text = getString(android.R.string.cancel)
            setOnClickListener { finish() }
        }
        bar.addView(progress)
        bar.addView(
            save,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        bar.addView(cancel)
        root.addView(bar)

        webView = WebView(this)
        root.addView(webView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            allowFileAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString ProDLAndroid/${BuildConfig.VERSION_NAME}"
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                progress.visibility = View.GONE
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.loadUrl(LOGIN_URL)
    }

    private fun persistCookies() {
        val expiry = System.currentTimeMillis() / 1000 + COOKIE_LIFETIME_SECONDS
        val cookieManager = CookieManager.getInstance()
        val rows = COOKIE_HOSTS.flatMap { host ->
            NetscapeCookies.fromCookieHeader(host, cookieManager.getCookie(host), expiry)
        }
        val unique = NetscapeCookies.dedupe(rows)
        if (unique.isEmpty()) {
            toast(getString(R.string.cookies_empty))
            return
        }
        val store = CookieStore(storeFile())
        val result = runCatching { store.save(NetscapeCookies.serialize(unique)) }
        if (result.isSuccess) {
            toast(getString(R.string.cookies_saved) + " (${result.getOrNull()?.cookieCount ?: unique.size})")
            setResult(Activity.RESULT_OK)
            finish()
        } else {
            toast(getString(R.string.save_failed))
        }
    }

    private fun storeFile(): File {
        val server = (application as ProDLApplication).server
        return server?.config?.cookiesFile ?: File(filesDir, "prodl/cookies.txt")
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val LOGIN_URL =
            "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fwww.youtube.com%2F"
        private const val COOKIE_LIFETIME_SECONDS = 400L * 24 * 3600

        private val COOKIE_HOSTS = listOf(
            "https://www.youtube.com",
            "https://m.youtube.com",
            "https://accounts.google.com",
            "https://www.google.com",
        )
    }
}
