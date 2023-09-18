package test.android.ble

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import test.android.ble.module.app.Permission
import test.android.ble.module.router.RouterScreen
import test.android.ble.util.android.isAllGranted
import test.android.ble.util.android.isGranted
import test.android.ble.util.android.notGranted
import test.android.ble.util.android.showToast
import test.android.ble.util.compose.isAllGranted
import test.android.ble.util.compose.isAllRequested
import test.android.ble.util.compose.notGranted
import test.android.ble.util.compose.permissionsAsFlow
import test.android.ble.util.compose.requestedState

internal class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "[Main]"
    }

    private val psLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { ps ->
        Log.d(TAG, "on ps result: $ps")
        lifecycleScope.launch {
            if (ps.keys.any(commonPs.value::containsKey)) {
                _commonPs.emit(
                    commonPs.value.toMutableMap().also {
                        ps.keys.forEach { key ->
                            it[key] = Permission(isGranted = ps[key] ?: false, requested = true)
                        }
                    }
                )
            }
            if (ps.keys.any(locPs.value::containsKey)) {
                _locPs.emit(
                    locPs.value.toMutableMap().also {
                        ps.keys.forEach { key ->
                            it[key] = Permission(isGranted = ps[key] ?: false, requested = true)
                        }
                    }
                )
            }
        }
    }
    private val _commonPs = MutableStateFlow(
        mutableListOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
        ).also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray().associateWith {
            Permission(isGranted = false, requested = false)
        }
    )
    private val commonPs = _commonPs.asStateFlow()
    private val _locPs = MutableStateFlow(
        mutableListOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ).also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                it.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }.toTypedArray().associateWith {
            Permission(isGranted = false, requested = false)
        }
    )
    private val locPs = _locPs.asStateFlow()

    private fun onPs(ps: Map<String, Permission>) {
        val notRequested = ps
            .filterNot { (_, it) -> it.requested }
            .keys
        if (notRequested.isEmpty()) {
            val notGranted = ps
                .filterNot { (_, it) -> it.isGranted }
                .keys
            showToast("No $notGranted permissions!")
            finish()
        } else {
            psLauncher.launch(notRequested.toTypedArray())
        }
    }

    private val commonPermissionsFlow = permissionsAsFlow(
        mutableListOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
        ).also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    )

    private val requestedState = requestedState()

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
                } else if (requestedState.collectAsState().isAllRequested(permissions)) {
                    showToast("No ${permissions.notGranted(context)} permissions!")
                    finish()
                } else {
                    requestedState.request(permissions)
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
                } else if (requestedState.collectAsState().isAllRequested(permissions)) {
                    showToast("No ${permissions.notGranted(context)} permissions!")
                    finish()
                } else {
                    requestedState.request(permissions)
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
