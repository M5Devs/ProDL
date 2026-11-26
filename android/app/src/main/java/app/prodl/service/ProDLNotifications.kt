package app.prodl.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import app.prodl.MainActivity
import app.prodl.R

/** Notification channels and the persistent "server is running" notification. */
object ProDLNotifications {
    const val CHANNEL_SERVER = "prodl_server"
    const val CHANNEL_PLAYBACK = "prodl_playback"
    const val SERVER_NOTIFICATION_ID = 1001

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val server = NotificationChannel(
            CHANNEL_SERVER,
            context.getString(R.string.server_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.server_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        val playback = NotificationChannel(
            CHANNEL_PLAYBACK,
            context.getString(R.string.playback_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
        }
        manager.createNotificationChannel(server)
        manager.createNotificationChannel(playback)
    }

    fun serverNotification(context: Context, port: Int, starting: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            context,
            1,
            Intent(context, ServerService::class.java).setAction(ServerService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (starting) {
            context.getString(R.string.starting_server)
        } else {
            context.getString(R.string.server_running, "127.0.0.1:$port")
        }
        return NotificationCompat.Builder(context, CHANNEL_SERVER)
            .setSmallIcon(R.drawable.ic_stat_prodl)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp)
            .addAction(0, context.getString(R.string.stop), stop)
            .build()
    }
}
