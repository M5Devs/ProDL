package app.prodl

import android.app.Application
import app.prodl.core.ServerConfig
import app.prodl.server.LocalServer
import app.prodl.service.ProDLNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Owns the single [LocalServer] instance. The server lives here (not in the
 * Activity) so downloads keep running while the UI is gone; [ServerService]
 * only promotes it to a foreground service.
 */
class ProDLApplication : Application() {
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
}
