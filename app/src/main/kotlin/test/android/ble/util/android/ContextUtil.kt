package test.android.ble.util.android

import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast

internal fun Context.showToast(message: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, message, duration).show()
}

internal fun Context.isAllGranted(permissions: Array<String>): Boolean {
    return permissions.all(::isGranted)
}

internal fun Context.isGranted(permission: String): Boolean {
    return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

internal fun Array<String>.granted(context: Context): List<String> {
    return filter { context.isGranted(it) }
}

internal fun Array<String>.notGranted(context: Context): List<String> {
    return filterNot { context.isGranted(it) }
}
