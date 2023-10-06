package test.android.ble

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import test.android.ble.module.app.AbstractViewModel
import test.android.ble.module.app.Injection
import test.android.ble.module.bluetooth.BLEGattService
import test.android.ble.module.bluetooth.BLEScannerService
import test.android.ble.provider.Contexts
import test.android.ble.provider.local.FinalLocalDataProvider
import test.android.ble.util.ForegroundUtil

internal class App : Application() {
    private var _lifecycle: Lifecycle? = null
    private val lifecycleObserver = LifecycleEventObserver { _, event: Lifecycle.Event ->
        when (event) {
            Lifecycle.Event.ON_START -> {
                // todo
            }
            else -> {
                // noop
            }
        }
    }

    private fun onState(state: BLEScannerService.State) {
        when (state) {
            BLEScannerService.State.STARTED -> {
                val intent = Intent(this, BLEScannerService::class.java)
                intent.action = BLEScannerService.ACTION_SCAN_STOP
                val notification = ForegroundUtil.buildNotification(
                    context = this,
                    title = "scanning...",
                    action = "stop",
                    intent = ForegroundUtil.getService(this, intent),
                )
                ForegroundUtil.notify(this, notification)
                BLEScannerService.startForeground(
                    context = this,
                    notificationId = ForegroundUtil.NOTIFICATION_ID,
                    notification,
                )
            }
            else -> {
                // noop
            }
        }
    }

    private fun startForeground(
        title: String,
        action: BLEGattService.Action,
        button: String,
    ) {
        val intent = BLEGattService.intent(context = this, action = action)
        val notification = ForegroundUtil.buildNotification(
            context = this,
            title = title,
            action = button,
            intent = ForegroundUtil.getService(this, intent),
        )
        ForegroundUtil.notify(context = this, notification = notification)
        BLEGattService.startForeground(
            context = this,
            notificationId = ForegroundUtil.NOTIFICATION_ID,
            notification,
        )
    }

    private fun startForeground(title: String) {
        val notification = ForegroundUtil.buildNotification(
            context = this,
            title = title,
        )
        ForegroundUtil.notify(this, notification = notification)
        BLEGattService.startForeground(
            context = this,
            notificationId = ForegroundUtil.NOTIFICATION_ID,
            notification = notification,
        )
    }

    private fun onEvent(event: BLEGattService.Event) {
        when (event) {
            is BLEGattService.Event.OnConnected -> {
                startForeground(
                    title = "connected ${event.address}",
                    action = BLEGattService.Action.DISCONNECT,
                    button = "disconnect",
                )
            }
            is BLEGattService.Event.OnConnecting -> {
                startForeground(title = "connecting ${event.address}...")
            }
            BLEGattService.Event.OnDisconnected -> {
                // noop
            }
            is BLEGattService.Event.OnDisconnecting -> {
                startForeground(title = "disconnecting ${event.address}...")
            }
            is BLEGattService.Event.OnSearchComing -> {
                startForeground(
                    title = "searching ${event.address}...",
                    action = BLEGattService.Action.SEARCH_STOP,
                    button = "stop",
                )
            }
            BLEGattService.Event.OnSearchWaiting -> {
                startForeground(
                    title = "search waiting...",
                    action = BLEGattService.Action.SEARCH_STOP,
                    button = "stop",
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        _lifecycle = lifecycle
        lifecycle.addObserver(lifecycleObserver)
        BLEScannerService.state
            .flowWithLifecycle(lifecycle, minActiveState = Lifecycle.State.CREATED)
            .onEach(::onState)
            .launchIn(lifecycle.coroutineScope)
        BLEGattService.event
            .flowWithLifecycle(lifecycle, minActiveState = Lifecycle.State.CREATED)
            .onEach(::onEvent)
            .launchIn(lifecycle.coroutineScope)
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
