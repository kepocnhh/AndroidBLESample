package test.android.ble

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import test.android.ble.module.app.Permissions
import test.android.ble.module.router.RouterScreen
import test.android.ble.util.android.isGranted
import test.android.ble.util.android.showToast

internal class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "[Main]"
        private val commonPermissions = Permissions(
            tag = "Com",
            mutableListOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
            ).also {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    it.add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }.toTypedArray()
        )
        private val locationQOrLowerPermissions = Permissions(
            tag = "Loc",
            mutableListOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ).also {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    it.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            }.toTypedArray()
        )
    }

    private val commonPermissionsLauncher = commonPermissions.registerForActivityResult(this) {
        _commonRequested.emit(true)
    }
    private val _commonRequested = MutableStateFlow(false)
    private val commonRequested = _commonRequested.asStateFlow()

    private val locationQOrLowerPermissionsLauncher = locationQOrLowerPermissions.registerForActivityResult(this) {
        _locationQOrLowerRequested.emit(true)
    }
    private val _locationQOrLowerRequested = MutableStateFlow(false)
    private val locationQOrLowerRequested = _locationQOrLowerRequested.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "on create...")
        super.onCreate(savedInstanceState)
        val context: Context = this
        setContent {
            Log.d(TAG, "composition...")
            BackHandler(onBack = ::finish)
            val isCommonGranted = commonPermissions.allGranted(context)
            Log.d(TAG, "isCommonGranted: $isCommonGranted")
            val isLocationQOrLowerGranted = locationQOrLowerPermissions.allGranted(context)
            Log.d(TAG, "isLocationQOrLowerGranted: $isLocationQOrLowerGranted")
            if (!isCommonGranted) {
                if (commonRequested.collectAsState().value) {
                    showToast("No ${commonPermissions.notGranted(context)} permissions!")
                    finish()
                } else {
                    Log.d(TAG, "permissions common ${commonPermissions.notGranted(context)} is not granted!")
                    commonPermissions.launch(commonPermissionsLauncher)
                }
            } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && !isLocationQOrLowerGranted) {
                if (locationQOrLowerRequested.collectAsState().value) {
                    showToast("No ${locationQOrLowerPermissions.notGranted(context)} permissions!")
                    finish()
                } else {
                    Log.d(TAG, "permissions location ${locationQOrLowerPermissions.notGranted(context)} is not granted!")
                    locationQOrLowerPermissions.launch(locationQOrLowerPermissionsLauncher)
                }
            } else if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q && !isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                val permission = Manifest.permission.ACCESS_BACKGROUND_LOCATION
                println("$TAG: permissions $permission is not granted!")
                finish() // todo
            } else {
                RouterScreen()
            }
        }
    }
}
