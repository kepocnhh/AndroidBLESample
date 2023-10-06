package test.android.ble.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import test.android.ble.R
import kotlin.math.absoluteValue

internal object ForegroundUtil {
    private val CHANNEL_ID = "${this::class.java.name}:CHANNEL"
    private const val CHANNEL_NAME = "BLE Sample"
    val NOTIFICATION_ID = System.currentTimeMillis().toInt().absoluteValue
    val ACTION_CLICK = "${this::class.java.name}:ACTION_CLICK"

    private fun Service.startForeground(notification: Notification) {
        notify(this, notification)
        startForeground(NOTIFICATION_ID, notification)
    }

    fun notify(context: Context, notification: Notification) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.checkChannel()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun Context.builder(title: String): NotificationCompat.Builder {
        val intent = Intent(ACTION_CLICK)
        val contentIntent = PendingIntent.getBroadcast(this, -1, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(R.drawable.bt)
            .setContentIntent(contentIntent)
    }

    private fun NotificationManager.checkChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel: NotificationChannel? = getNotificationChannel(CHANNEL_ID)
        if (channel == null) {
            createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
    }

    internal fun startForeground(
        service: Service,
        title: String,
    ) {
        service.startForeground(service.builder(title).build())
    }

    fun getService(
        context: Context,
        intent: Intent,
    ): PendingIntent {
        return PendingIntent.getService(context, -1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
    }

    fun buildNotification(
        context: Context,
        title: String,
        action: String,
        intent: PendingIntent,
    ): Notification {
        return context
            .builder(title)
            .addAction(-1, action, intent)
            .build()
    }

    fun buildNotification(
        context: Context,
        title: String,
    ): Notification {
        return context
            .builder(title)
            .build()
    }

    private fun startForeground(
        service: Service,
        title: String,
        action: String,
        intent: PendingIntent,
    ) {
        val notification = service
            .builder(title)
            .addAction(-1, action, intent)
            .build()
        service.startForeground(notification)
    }

    fun startForeground(
        service: Service,
        title: String,
        action: String,
        intent: Intent,
    ) {
        return startForeground(
            service = service,
            title = title,
            action = action,
            intent = PendingIntent.getService(service, -1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT),
        )
    }
}
