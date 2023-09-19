package test.android.ble

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import test.android.ble.module.router.RouterScreen
import test.android.ble.util.android.isAllGranted
import test.android.ble.util.android.isGranted
import test.android.ble.util.android.notGranted
import test.android.ble.util.android.showToast
import test.android.ble.util.compose.PermissionsRequester
import test.android.ble.util.compose.isAllRequested

internal class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "[Main]"
    }

    private val permissionsRequester = PermissionsRequester(this)

    @RequiresApi(Build.VERSION_CODES.R)
    @Composable
    private fun LocationGranted() {
        Log.d(TAG, "location granted...")
        val context = LocalContext.current
        val permissions = arrayOf(
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        if (isAllGranted(permissions)) {
            RouterScreen()
        } else if (permissionsRequester.collectAsState().isAllRequested(permissions)) {
            showToast("No ${permissions.notGranted(context)} permissions!")
            finish()
        } else {
            val permission = Manifest.permission.ACCESS_BACKGROUND_LOCATION
            if (shouldShowRequestPermissionRationale(permission)) {
                Log.d(TAG, "should show request permission $permission rationale...")
                val label = packageManager.backgroundPermissionOptionLabel
                    .takeIf { it.isNotEmpty() }
                    ?: "Background permission option label." // todo
                AlertDialog
                    .Builder(this)
                    .setCancelable(false)
                    .setTitle(label)
                    .setMessage("Need $permission permission.")
                    .setPositiveButton("to permission") { _,  _ ->
                        permissionsRequester.request(arrayOf(permission))
                    }
                    .show()
            } else {
                Log.d(TAG, "request permission $permission...")
                permissionsRequester.request(arrayOf(permission))
            }
        }
    }

    @Composable
    private fun GrantedOrFinish(
        permissions: Array<String>,
        onGranted: @Composable () -> Unit,
    ) {
        if (isAllGranted(permissions)) {
            onGranted()
        } else if (permissionsRequester.collectAsState().isAllRequested(permissions)) {
            val context = LocalContext.current
            showToast("No ${permissions.notGranted(context)} permissions!")
            finish()
        } else {
            permissionsRequester.request(permissions)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "on create...")
        super.onCreate(savedInstanceState)
        val context: Context = this
        setContent {
            Log.d(TAG, "composition...")
            BackHandler(onBack = ::finish)
            Log.d(TAG, "Build.VERSION.SDK_INT: ${Build.VERSION.SDK_INT}")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                val permissions = arrayOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                )
                GrantedOrFinish(permissions) {
                    RouterScreen()
                }
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                val permissions = arrayOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                )
                GrantedOrFinish(permissions) {
                    RouterScreen()
                }
            } else {
                // Build.VERSION.SDK_INT > Build.VERSION_CODES.Q
                val permissions = arrayOf(
//                    Manifest.permission.BLUETOOTH,
//                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                )
                if (!isAllGranted(permissions)) {
                    Log.d(TAG, permissions.associateWith { isGranted(it) }.toString())
                    if (permissionsRequester.collectAsState().isAllRequested(permissions)) {
                        showToast("No ${permissions.notGranted(context)} permissions!")
                        finish()
                    } else {
                        permissionsRequester.request(permissions)
                    }
                } else {
                    LocationGranted()
                }
            }
        }
    }
}
