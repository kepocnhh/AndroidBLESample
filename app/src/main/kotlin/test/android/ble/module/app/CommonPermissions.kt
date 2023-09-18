package test.android.ble.module.app

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import test.android.ble.util.android.allGranted
import test.android.ble.util.android.granted
import test.android.ble.util.android.granted
import test.android.ble.util.android.notGranted

internal object CommonPermissions {
    private val permissions = mutableListOf(
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ).also {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            it.add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    fun launch(launcher: ActivityResultLauncher<Array<String>>) {
        launcher.launch(permissions)
    }

    fun granted(context: Context): List<String> {
        return permissions.granted(context)
    }

    fun notGranted(context: Context): List<String> {
        return permissions.notGranted(context)
    }

    fun allGranted(context: Context): Boolean {
        return context.allGranted(permissions)
    }
}
