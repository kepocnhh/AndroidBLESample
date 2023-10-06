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
    sealed interface Broadcast {
        object OnDisconnected : Broadcast
        class OnConnecting(val address: String) : Broadcast
        class OnConnected(val address: String) : Broadcast
        class OnDisconnecting(val address: String) : Broadcast
        object OnSearchWaiting : Broadcast
        class OnSearchComing(val address: String) : Broadcast
    }

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
    private var gattState: BLEGattService.State = BLEGattService.State.Disconnected
    private var scannerState: BLEScannerService.State = BLEScannerService.State.NONE

    private fun onState(newState: BLEScannerService.State) {
        val oldState = scannerState
        scannerState = newState
        when (newState) {
            BLEScannerService.State.STARTED -> {
                if (oldState == BLEScannerService.State.STARTED) return
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

    private suspend fun onState(newState: BLEGattService.State) {
        val oldState = gattState
        gattState = newState
        when (newState) {
            is BLEGattService.State.Connected -> {
                if (oldState is BLEGattService.State.Connected) return
                _broadcast.emit(Broadcast.OnConnected(address = newState.address))
            }
            is BLEGattService.State.Connecting -> {
                if (oldState is BLEGattService.State.Connecting) return
                _broadcast.emit(Broadcast.OnConnecting(address = newState.address))
            }
            BLEGattService.State.Disconnected -> {
                if (oldState is BLEGattService.State.Disconnected) return
                _broadcast.emit(Broadcast.OnDisconnected)
            }
            is BLEGattService.State.Disconnecting -> {
                if (oldState is BLEGattService.State.Disconnecting) return
                _broadcast.emit(Broadcast.OnDisconnecting(address = newState.address))
            }
            is BLEGattService.State.Search -> {
                when (newState.type) {
                    BLEGattService.State.Search.Type.WAITING -> {
                        if (oldState is BLEGattService.State.Search && oldState.type == BLEGattService.State.Search.Type.WAITING) return
                        _broadcast.emit(Broadcast.OnSearchWaiting)
                    }
                    BLEGattService.State.Search.Type.COMING -> {
                        if (oldState is BLEGattService.State.Search && oldState.type == BLEGattService.State.Search.Type.COMING) return
                        _broadcast.emit(Broadcast.OnSearchComing(address = newState.address))
                    }
                    else -> {
                        // noop
                    }
                }
            }
        }
    }

    private fun onBroadcast(broadcast: Broadcast) {
        when (broadcast) {
            is Broadcast.OnConnected -> {
                val intent = BLEGattService.intent(this, BLEGattService.Action.DISCONNECT)
                val notification = ForegroundUtil.buildNotification(
                    context = this,
                    title = "connected ${broadcast.address}",
                    action = "disconnect",
                    intent = ForegroundUtil.getService(this, intent),
                )
                ForegroundUtil.notify(this, notification)
                BLEGattService.startForeground(
                    context = this,
                    notificationId = ForegroundUtil.NOTIFICATION_ID,
                    notification,
                )
            }
            is Broadcast.OnConnecting -> {
                val notification = ForegroundUtil.buildNotification(
                    context = this,
                    title = "connecting ${broadcast.address}...",
                )
                ForegroundUtil.notify(this, notification)
                BLEGattService.startForeground(
                    this,
                    notificationId = ForegroundUtil.NOTIFICATION_ID,
                    notification,
                )
            }
            Broadcast.OnDisconnected -> {
                // noop
            }
            is Broadcast.OnDisconnecting -> {
                val notification = ForegroundUtil.buildNotification(
                    context = this,
                    title = "disconnecting ${broadcast.address}...",
                )
                ForegroundUtil.notify(this, notification)
                BLEGattService.startForeground(
                    this,
                    notificationId = ForegroundUtil.NOTIFICATION_ID,
                    notification,
                )
            }
            is Broadcast.OnSearchComing -> {
                val intent = BLEGattService.intent(this, BLEGattService.Action.SEARCH_STOP)
                val notification = ForegroundUtil.buildNotification(
                    context = this,
                    title = "searching ${broadcast.address}...",
                    action = "stop",
                    intent = ForegroundUtil.getService(this, intent),
                )
                ForegroundUtil.notify(this, notification)
                BLEGattService.startForeground(
                    this,
                    notificationId = ForegroundUtil.NOTIFICATION_ID,
                    notification,
                )
            }
            Broadcast.OnSearchWaiting -> {
                val intent = BLEGattService.intent(this, BLEGattService.Action.SEARCH_STOP)
                val notification = ForegroundUtil.buildNotification(
                    context = this,
                    title = "search waiting...",
                    action = "stop",
                    intent = ForegroundUtil.getService(this, intent),
                )
                ForegroundUtil.notify(this, notification)
                BLEGattService.startForeground(
                    context = this,
                    notificationId = ForegroundUtil.NOTIFICATION_ID,
                    notification,
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
            .flowWithLifecycle(lifecycle)
            .onEach(::onState)
            .launchIn(lifecycle.coroutineScope)
        BLEGattService.state
            .flowWithLifecycle(lifecycle)
            .onEach(::onState)
            .launchIn(lifecycle.coroutineScope)
        broadcast
            .flowWithLifecycle(lifecycle)
            .onEach(::onBroadcast)
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
        private val _broadcast = MutableSharedFlow<Broadcast>()
        val broadcast = _broadcast.asSharedFlow()

        private var _viewModelFactory: ViewModelProvider.Factory? = null

        @Composable
        inline fun <reified T : AbstractViewModel> viewModel(): T {
            return viewModel(factory = checkNotNull(_viewModelFactory))
        }
    }
}
