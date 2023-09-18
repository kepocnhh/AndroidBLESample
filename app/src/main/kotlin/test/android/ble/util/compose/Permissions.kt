package test.android.ble.util.compose

import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.State
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal fun AppCompatActivity.permissionsRequester(): PermissionsRequester {
//    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) TODO()
    return PermissionsRequester(this)
}

internal fun State<Map<Int, Boolean>>.isAllRequested(permissions: Array<String>): Boolean {
    val key = permissions.sorted().hashCode()
    return value[key] ?: false
}

internal class PermissionsRequester private constructor(
    activity: AppCompatActivity,
    private val flow: MutableStateFlow<Map<Int, Boolean>>,
) : StateFlow<Map<Int, Boolean>> by flow {
    constructor(activity: AppCompatActivity) : this(activity, MutableStateFlow(emptyMap()))

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val map = flow.value.toMutableMap()
        val key = it.keys.sorted().hashCode()
        map[key] = true
        flow.value = map
    }

    fun request(permissions: Array<String>) {
        launcher.launch(permissions)
    }
}
