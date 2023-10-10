package test.android.ble.util.android

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent

internal object ServiceUtil {
    val ACTION_START_FOREGROUND = "${this::class.java.name}:ACTION_START_FOREGROUND"
    val ACTION_STOP_FOREGROUND = "${this::class.java.name}:ACTION_STOP_FOREGROUND"

    fun getNotificationId(intent: Intent): Int {
        if (!intent.hasExtra("notificationId")) TODO()
        return intent.getIntExtra("notificationId", -1)
    }

    fun getNotification(intent: Intent): Notification {
        if (!intent.hasExtra("notification")) TODO()
        return intent.getParcelableExtra("notification") ?: TODO()
    }

    fun getNotificationBehavior(intent: Intent): Int {
        if (!intent.hasExtra("notificationBehavior")) TODO()
        val result = intent.getIntExtra("notificationBehavior", -1)
        when (result) {
            Service.STOP_FOREGROUND_REMOVE -> {
                // noop
            }
            else -> TODO()
        }
        return result
    }

    @JvmStatic
    fun <T : Service> startForeground(
        context: Context,
        type: Class<T>,
        notificationId: Int,
        notification: Notification,
    ) {
        val intent = Intent(context, type)
        intent.action = ACTION_START_FOREGROUND
        intent.putExtra("notificationId", notificationId)
        intent.putExtra("notification", notification)
        context.startService(intent)
    }

    @JvmStatic
    fun <T : Service> stopForeground(
        context: Context,
        type: Class<T>,
        notificationBehavior: Int,
    ) {
        when (notificationBehavior) {
            Service.STOP_FOREGROUND_REMOVE -> {
                // noop
            }
            else -> TODO()
        }
        val intent = Intent(context, type)
        intent.action = ACTION_STOP_FOREGROUND
        intent.putExtra("notificationBehavior", notificationBehavior)
        context.startService(intent)
    }
}

internal inline fun <reified T : Service> Context.startForeground(
    notificationId: Int,
    notification: Notification,
) {
    ServiceUtil.startForeground(
        context = this,
        type = T::class.java,
        notificationId = notificationId,
        notification = notification,
    )
}

internal inline fun <reified T : Service> Context.stopForeground(
    notificationBehavior: Int = Service.STOP_FOREGROUND_REMOVE,
) {
    ServiceUtil.stopForeground(
        context = this,
        type = T::class.java,
        notificationBehavior = notificationBehavior,
    )
}
