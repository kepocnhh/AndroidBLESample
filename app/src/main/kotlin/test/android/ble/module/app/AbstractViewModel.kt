package test.android.ble.module.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal open class AbstractViewModel : ViewModel() {
    protected fun Injection.launch(block: suspend CoroutineScope.() -> Unit) {
        viewModelScope.launch(contexts.main, block = block)
    }
}
