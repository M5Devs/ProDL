package app.prodl.bridge

import android.os.Build
import android.webkit.JavascriptInterface
import android.widget.Toast
import app.prodl.BuildConfig
import app.prodl.MainActivity
import app.prodl.playback.PlayerActivity
import org.json.JSONObject

/**
 * `window.ProDLNative` — the only surface the shared SPA can use to reach
 * Android-only features. Every method is safe to call from the JS thread.
 */
class NativeBridge(private val activity: MainActivity) {

    @JavascriptInterface
    fun platform(): String = "android"

    @JavascriptInterface
    fun version(): String = BuildConfig.VERSION_NAME

    @JavascriptInterface
    fun apiBase(): String = activity.apiBase()

    @JavascriptInterface
    fun serverStatus(): String = JSONObject()
        .put("running", activity.serverPort() != 0)
        .put("base_url", activity.apiBase())
        .put("port", activity.serverPort())
        .toString()

    @JavascriptInterface
    fun playMedia(
        url: String?,
        title: String?,
        thumbnail: String?,
        audioOnly: Boolean,
        sponsorblockJson: String?,
    ) {
        activity.runOnUiThread {
            PlayerActivity.start(activity, url, title, thumbnail, audioOnly, sponsorblockJson)
        }
    }

    @JavascriptInterface
    fun openYouTubeLogin() {
        activity.runOnUiThread { activity.openCookieLogin() }
    }

    @JavascriptInterface
    fun checkPermissions(): String = JSONObject()
        .put("notifications", activity.notificationPermissionState())
        .put("post_notifications_required", Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        .put("save_requires_storage_permission", Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
        .put("sdk_int", Build.VERSION.SDK_INT)
        .toString()

    @JavascriptInterface
    fun requestPermissions() {
        activity.runOnUiThread { activity.requestNotificationPermission() }
    }

    @JavascriptInterface
    fun saveToDownloads(taskId: String?) {
        activity.runOnUiThread { activity.saveTaskToDownloads(taskId) }
    }

    @JavascriptInterface
    fun toast(message: String?) {
        val text = message?.take(300) ?: return
        activity.runOnUiThread { Toast.makeText(activity, text, Toast.LENGTH_SHORT).show() }
    }
}
