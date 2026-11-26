package app.prodl.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import app.prodl.ProDLApplication
import app.prodl.server.LocalServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that keeps the loopback API server (and therefore every
 * running download) alive while the screen is off or the UI is dismissed.
 */
class ServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val starting = AtomicBoolean(false)
    private lateinit var server: LocalServer

    override fun onCreate() {
        super.onCreate()
        ProDLNotifications.createChannels(this)
        server = (application as ProDLApplication).server ?: LocalServer(this).also {
            Log.w(TAG, "application server missing; starting a standalone instance")
        }
        startForegroundCompat(ProDLNotifications.serverNotification(this, 0, starting = true))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!starting.compareAndSet(false, true)) return START_STICKY
        scope.launch {
            try {
                val port = server.start()
                updateNotification(port)
                Log.i(TAG, "local API listening on 127.0.0.1:$port")
            } catch (t: Throwable) {
                Log.e(TAG, "failed to start the local server", t)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { server.stop() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification(port: Int) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(
            ProDLNotifications.SERVER_NOTIFICATION_ID,
            ProDLNotifications.serverNotification(this, port, starting = false),
        )
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                ProDLNotifications.SERVER_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(ProDLNotifications.SERVER_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_START = "app.prodl.action.START_SERVER"
        const val ACTION_STOP = "app.prodl.action.STOP_SERVER"
        private const val TAG = "ProDL.ServerService"

        fun start(context: android.content.Context) {
            context.startForegroundService(Intent(context, ServerService::class.java).setAction(ACTION_START))
        }

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, ServerService::class.java).setAction(ACTION_STOP))
        }
    }
}
