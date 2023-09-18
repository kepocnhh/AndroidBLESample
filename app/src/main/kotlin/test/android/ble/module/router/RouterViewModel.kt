package test.android.ble.module.router

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import test.android.ble.module.app.AbstractViewModel
import test.android.ble.module.app.Injection
import test.android.ble.util.Optional

internal class RouterViewModel(private val injection: Injection) : AbstractViewModel() {
    private val _address = MutableStateFlow<Optional<String>?>(null)
    val address = _address.asStateFlow()

    fun requestAddress() {
        injection.launch {
            val value = withContext(injection.contexts.default) {
                injection.local.address
            }
            _address.value = when (value) {
                null -> Optional.None
                else -> Optional.Some(value)
            }
        }
    }

    fun setAddress(value: String) {
        injection.launch {
            withContext(injection.contexts.default) {
                injection.local.address = value
            }
            _address.value = Optional.Some(value)
        }
    }

    fun forgetAddress() {
        injection.launch {
            withContext(injection.contexts.default) {
                injection.local.address = null
            }
            _address.value = Optional.None
        }
    }
}
