package app.prodl

import android.app.Application
import app.prodl.core.ServerConfig
import app.prodl.server.LocalServer
import app.prodl.service.ProDLNotifications
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Owns the single [LocalServer] instance. The server lives here (not in the
 * Activity) so downloads keep running while the UI is gone; [ServerService]
 * only promotes it to a foreground service when the user turns it on.
 *
 * The HTTP engine itself is no longer started at launch: the Compose UI calls
 * the same engines in-process, and the loopback API is opt-in (Settings).
 */
class ProDLApplication : Application(), SingletonImageLoader.Factory {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    var server: LocalServer? = null
        private set

    override fun onCreate() {
        super.onCreate()
        ProDLNotifications.createChannels(this)
        val instance = LocalServer(this, ServerConfig.default(this))
        instance.prepare()
        server = instance
    }

    /** Thumbnails: OkHttp fetcher with the shared disk cache. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .crossfade(true)
            .build()
}
