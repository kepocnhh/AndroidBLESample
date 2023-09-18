package test.android.ble

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import test.android.ble.module.app.AbstractViewModel
import test.android.ble.module.app.Injection
import test.android.ble.provider.Contexts
import test.android.ble.provider.local.FinalLocalDataProvider

internal class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val injection = Injection(
            local = FinalLocalDataProvider(this),
            contexts = Contexts(
                main = Dispatchers.Main,
                default = Dispatchers.Default,
            ),
        )
        _viewModelFactory = object : ViewModelProvider.Factory {
            override fun <U : ViewModel> create(modelClass: Class<U>): U {
                return modelClass.getConstructor(Injection::class.java).newInstance(injection)
            }
        }
    }

    companion object {
        private var _viewModelFactory: ViewModelProvider.Factory? = null

        @Composable
        inline fun <reified T : AbstractViewModel> viewModel(): T {
            return viewModel(factory = checkNotNull(_viewModelFactory))
        }
    }
}
