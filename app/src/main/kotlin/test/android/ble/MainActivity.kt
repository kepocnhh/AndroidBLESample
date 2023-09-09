package test.android.ble

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import test.android.ble.module.router.RouterScreen
import test.android.ble.util.android.showToast

internal class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "[Main]"
        private val permissions = mutableListOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ).also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.add(Manifest.permission.POST_NOTIFICATIONS,)
            }
        }.toTypedArray()
    }

    private val requestPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val isGranted = permissions.all { (_, isGranted) -> isGranted }
        if (isGranted) {
            lifecycleScope.launch {
                _granted.emit(true)
            }
        } else {
            val notGranted = permissions.keys.filter { permissions[it] == false }
            println("$TAG: permissions $notGranted not granted!")
            showToast("No $notGranted permissions!")
            finish()
        }
    }
    private val _granted = MutableStateFlow(false)
    private val granted = _granted.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BackHandler(onBack = ::finish)
            val isGranted = granted.collectAsState(
                initial = permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED },
            ).value
            if (isGranted) {
                RouterScreen()
            } else {
                println("$TAG: permissions ${permissions.toList()} is not granted!")
                requestPermissionsLauncher.launch(permissions)
            }
        }
    }
}
