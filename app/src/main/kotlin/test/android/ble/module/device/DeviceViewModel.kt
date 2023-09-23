package test.android.ble.module.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import test.android.ble.module.app.AbstractViewModel
import test.android.ble.module.app.Injection

internal class DeviceViewModel(private val injection: Injection) : AbstractViewModel() {
    private val _writes = MutableStateFlow<Set<String>?>(null)
    val writes = _writes.asStateFlow()

    fun requestWrites() {
        injection.launch {
            _writes.value = withContext(injection.contexts.default) {
                injection.local.writes
            }
        }
    }

    fun clearWrites() {
        injection.launch {
            _writes.value = withContext(injection.contexts.default) {
                emptySet<String>().also {
                    injection.local.writes = it
                }
            }
        }
    }

    fun write(value: String) {
        injection.launch {
            _writes.value = withContext(injection.contexts.default) {
                val oldValues = injection.local.writes
                if (!oldValues.contains(value)) {
                    injection.local.writes = injection.local.writes + value
                }
                injection.local.writes
            }
        }
    }
}
