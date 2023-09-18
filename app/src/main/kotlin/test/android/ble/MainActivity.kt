package test.android.ble

import android.Manifest
import android.content.Context
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
import test.android.ble.module.app.CommonPermissions
import test.android.ble.module.app.LocationPermissions
import test.android.ble.module.router.RouterScreen
import test.android.ble.util.android.isGranted
import test.android.ble.util.android.showToast

internal class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "[Main]"
        private val location = mutableListOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ).also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                it.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }.toTypedArray()
    }

    private val commonPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val isGranted = permissions.all { (_, isGranted) -> isGranted }
        if (isGranted) {
            lifecycleScope.launch {
                _commonGranted.emit(true)
            }
        } else {
            val notGranted = permissions.keys.filter { permissions[it] == false }
            println("$TAG: permissions $notGranted not granted!")
            showToast("No $notGranted permissions!")
            finish()
        }
    }
    private val _commonGranted = MutableStateFlow(false)
    private val commonGranted = _commonGranted.asStateFlow()

    private val locationQOrLowerPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val isGranted = permissions.all { (_, isGranted) -> isGranted }
        if (isGranted) {
            lifecycleScope.launch {
                _locationQOrLowerGranted.emit(true)
            }
        } else {
            val notGranted = permissions.keys.filter { permissions[it] == false }
            println("$TAG: permissions $notGranted not granted!")
            showToast("No $notGranted permissions!")
            finish()
        }
    }
    private val _locationQOrLowerGranted = MutableStateFlow(false)
    private val locationQOrLowerGranted = _locationQOrLowerGranted.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val context: Context = this
        setContent {
            BackHandler(onBack = ::finish)
            val isCommonGranted = commonGranted.collectAsState(
                initial = CommonPermissions.allGranted(context),
            ).value
            val isLocationQOrLowerGranted = locationQOrLowerGranted.collectAsState(
                initial = LocationPermissions.QOrLower.allGranted(context),
            ).value
            if (!isCommonGranted) {
                println("$TAG: permissions ${CommonPermissions.notGranted(context)} is not granted!")
                CommonPermissions.launch(commonPermissionsLauncher)
            } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && !isLocationQOrLowerGranted) {
                println("$TAG: permissions ${LocationPermissions.QOrLower.notGranted(context)} is not granted!")
                LocationPermissions.QOrLower.launch(locationQOrLowerPermissionsLauncher)
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
