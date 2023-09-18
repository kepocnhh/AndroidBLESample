package test.android.ble.module.app

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import test.android.ble.util.android.allGranted
import test.android.ble.util.android.notGranted

internal object LocationPermissions {
    object QOrLower {
        private val _permissions = mutableListOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ).also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                it.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }.toTypedArray()

        fun allGranted(context: Context): Boolean {
            return context.allGranted(_permissions)
        }

        fun notGranted(context: Context): List<String> {
            return _permissions.notGranted(context)
        }

        fun launch(launcher: ActivityResultLauncher<Array<String>>) {
            launcher.launch(_permissions)
        }
    }
}
