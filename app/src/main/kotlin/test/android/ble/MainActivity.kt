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
import test.android.ble.module.router.RouterScreen
import test.android.ble.util.android.isAllGranted
import test.android.ble.util.android.notGranted
import test.android.ble.util.android.showToast
import test.android.ble.util.compose.isAllRequested
import test.android.ble.util.compose.permissionsRequester

internal class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "[Main]"
    }

    private val permissionsRequester = permissionsRequester()

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "on create...")
        super.onCreate(savedInstanceState)
        val context: Context = this
        setContent {
            Log.d(TAG, "composition...")
            BackHandler(onBack = ::finish)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                val permissions = arrayOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                )
                if (isAllGranted(permissions)) {
                    RouterScreen()
                } else if (permissionsRequester.collectAsState().isAllRequested(permissions)) {
                    showToast("No ${permissions.notGranted(context)} permissions!")
                    finish()
                } else {
                    permissionsRequester.request(permissions)
                }
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                val permissions = arrayOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                )
                if (isAllGranted(permissions)) {
                    RouterScreen()
                } else if (permissionsRequester.collectAsState().isAllRequested(permissions)) {
                    showToast("No ${permissions.notGranted(context)} permissions!")
                    finish()
                } else {
                    permissionsRequester.request(permissions)
                }
            } else {
                TODO()
            }
            /*
            val commonPsState = commonPs.collectAsState()
            val locPsState = locPs.collectAsState()
            if (!commonPsState.value.all { (_, it) -> it.isGranted }) {
                Log.d(TAG, "common is not granted!")
                onPs(commonPsState.value)
            } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && !locPsState.value.all { (_, it) -> it.isGranted }) {
                Log.d(TAG, "loc is not granted!")
                onPs(locPsState.value)
            } else if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q && !isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                val permission = Manifest.permission.ACCESS_BACKGROUND_LOCATION
                println("$TAG: permissions $permission is not granted!")
                if (shouldShowRequestPermissionRationale(permission)) {
                    val label = packageManager
                        .backgroundPermissionOptionLabel
                        .takeIf { it.isNotEmpty() }
                        ?: "Background permission option label."
                    AlertDialog
                        .Builder(this)
                        .setTitle(label)
                        .setMessage("Build.VERSION.SDK_INT: ${Build.VERSION.SDK_INT} need permission $permission!")
                        .setPositiveButton("to settings", null)
                        .setOnDismissListener {
                            onPs(locPsState.value)
//                            requestPermissions(
//                                arrayOf(
//                                    Manifest.permission.ACCESS_COARSE_LOCATION,
//                                    Manifest.permission.ACCESS_FINE_LOCATION,
//                                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
//                                ),
//                                1
//                            )
//                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
//                            intent.data = Uri.fromParts("package", packageName, null)
//                            startActivity(intent)
//                            finish()
                        }
                        .show()
                } else {
                    TODO("shouldShowRequestPermissionRationale false")
                }
            } else {
                RouterScreen()
            }
            */
        }
    }
}
