package test.android.ble.util

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
    private val NOTIFICATION_ID = System.currentTimeMillis().toInt().absoluteValue

    private fun startForeground(
        service: Service,
        title: String,
        action: String,
        intent: PendingIntent,
    ) {
        val notificationManager = service.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel: NotificationChannel? = notificationManager.getNotificationChannel(CHANNEL_ID)
            if (channel == null) {
                notificationManager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
        }
        val notification = NotificationCompat.Builder(service, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(R.drawable.bt)
            .addAction(-1, action, intent)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
        service.startForeground(NOTIFICATION_ID, notification)
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
