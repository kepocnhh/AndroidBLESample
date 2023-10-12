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
import test.android.ble.util.android.startForeground
import kotlin.math.absoluteValue

internal object ForegroundUtil {
    private val CHANNEL_ID = "${this::class.java.name}:CHANNEL"
    private const val CHANNEL_NAME = "BLE Sample"
    val NOTIFICATION_ID = System.currentTimeMillis().toInt().absoluteValue

    fun notify(context: Context, notification: Notification) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.checkChannel()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun Context.builder(title: String): NotificationCompat.Builder {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(R.drawable.bt)
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
}

internal inline fun <reified T : Service> Context.notifyAndStartForeground(
    intent: Intent,
    title: String,
    button: String,
) {
    val notification = ForegroundUtil.buildNotification(
        context = this,
        title = title,
        action = button,
        intent = ForegroundUtil.getService(this, intent),
    )
    ForegroundUtil.notify(context = this, notification = notification)
    startForeground<T>(
        notificationId = ForegroundUtil.NOTIFICATION_ID,
        notification = notification,
    )
}

internal inline fun <reified T : Service> Context.notifyAndStartForeground(title: String) {
    val notification = ForegroundUtil.buildNotification(
        context = this,
        title = title,
    )
    ForegroundUtil.notify(this, notification = notification)
    startForeground<T>(
        notificationId = ForegroundUtil.NOTIFICATION_ID,
        notification = notification,
    )
}
