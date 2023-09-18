package test.android.ble.module.app

import android.content.Context
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import test.android.ble.util.android.allGranted
import test.android.ble.util.android.notGranted

internal class Permissions(
    tag: String,
    private val permissions: Array<String>,
) {
    private val TAG = "[Permissions|$tag]"

    fun allGranted(context: Context): Boolean {
        return context.allGranted(permissions)
    }

    fun notGranted(context: Context): List<String> {
        return permissions.notGranted(context)
    }

    fun launch(launcher: ActivityResultLauncher<Array<String>>) {
        Log.d(TAG, "launch...")
        launcher.launch(permissions)
    }

    @Composable
    fun collectAsState(flow: StateFlow<Boolean>, context: Context): State<Boolean> {
        val initial = context.allGranted(permissions)
        Log.d(TAG, "collect as state: initial $initial")
        return flow.collectAsState(initial = initial)
    }

    fun registerForActivityResult(activity: AppCompatActivity, onRequested: suspend () -> Unit): ActivityResultLauncher<Array<String>> {
        return activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            activity.lifecycleScope.launch {
                onRequested()
            }
        }
    }
}
