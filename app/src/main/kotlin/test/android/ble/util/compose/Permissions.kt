package test.android.ble.util.compose

import android.content.Context
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class Permission(
    val isGranted: Boolean,
    val isRequested: Boolean,
)

internal fun AppCompatActivity.requestedState(): RequestedState {
    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) TODO()
    return RequestedState(this, MutableStateFlow(emptyMap()))
}

internal fun State<Map<Int, Boolean>>.isAllRequested(permissions: Array<String>): Boolean {
    return value[permissions.sorted().hashCode()] ?: false
}

internal class RequestedState(
    activity: AppCompatActivity,
    private val flow: MutableStateFlow<Map<Int, Boolean>>,
) : StateFlow<Map<Int, Boolean>> by flow {
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val map = flow.value.toMutableMap()
        map[it.keys.sorted().hashCode()] = true
        flow.value = map
    }

//    fun isAllGranted(): Boolean {
//        return flow.value.all { (_, it) -> it.isGranted }
//    }
//
//    fun isAllRequested(): Boolean {
//        return flow.value.all { (_, it) -> it.isRequested }
//    }
//
//    fun notGranted(): Set<String> {
//        return flow.value.filterNot { (_, it) -> it.isGranted }.keys
//    }

    fun request(permissions: Array<String>) {
        launcher.launch(permissions)
    }
}

internal class PermissionsState(
    activity: AppCompatActivity,
    private val flow: MutableStateFlow<Map<String, Permission>>,
) : StateFlow<Map<String, Permission>> by flow {
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val map = flow.value.toMutableMap()
        it.keys.forEach { key ->
            map[key] = Permission(isGranted = it[key] ?: false, isRequested = true)
        }
        flow.value = map
    }

//    fun isAllGranted(): Boolean {
//        return flow.value.all { (_, it) -> it.isGranted }
//    }
//
//    fun isAllRequested(): Boolean {
//        return flow.value.all { (_, it) -> it.isRequested }
//    }
//
//    fun notGranted(): Set<String> {
//        return flow.value.filterNot { (_, it) -> it.isGranted }.keys
//    }

    fun request() {
        launcher.launch(flow.value.keys.toTypedArray())
    }
}

internal class PermissionsRequester(
    activity: AppCompatActivity,
    private val flow: MutableStateFlow<Map<String, Permission>>,
) {
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val map = flow.value.toMutableMap()
        it.keys.forEach { key ->
            map[key] = Permission(isGranted = it[key] ?: false, isRequested = true)
        }
        flow.value = map
    }

    fun request() {
        launcher.launch(flow.value.keys.toTypedArray())
    }
}

internal fun AppCompatActivity.flowAndRequester(
    permissions: Iterable<String>,
): Pair<StateFlow<Map<String, Permission>>, PermissionsRequester> {
    val map = permissions.associateWith {
        Permission(isGranted = false, isRequested = false)
    }
    val flow =  MutableStateFlow(map)
    return flow to PermissionsRequester(this, flow)
}

internal fun AppCompatActivity.permissionsAsFlow(
    permissions: Iterable<String>,
): PermissionsState {
    val map = permissions.associateWith {
        Permission(isGranted = false, isRequested = false)
    }
    return PermissionsState(this, MutableStateFlow(map))
}

internal fun Map<String, Permission>.isAllGranted(): Boolean {
    return all { (_, it) -> it.isGranted }
}

internal fun Map<String, Permission>.isAllRequested(): Boolean {
    return all { (_, it) -> it.isRequested }
}

internal fun Map<String, Permission>.notGranted(): Set<String> {
    return filterNot { (_, it) -> it.isGranted }.keys
}
