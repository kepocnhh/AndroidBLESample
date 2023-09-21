package test.android.ble.module.bluetooth

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass.Device
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import test.android.ble.entity.BTDevice
import test.android.ble.util.ForegroundUtil
import test.android.ble.util.android.isBTEnabled
import test.android.ble.util.android.onBTEnabled
import test.android.ble.util.android.requireBTAdapter
import test.android.ble.util.android.scanStart
import test.android.ble.util.android.scanStop
import kotlin.time.Duration.Companion.seconds

internal class BLEGattService : Service() {
    sealed interface Broadcast {
        class OnError(val error: Throwable) : Broadcast
    }

    sealed interface State {
        data class Connecting(val address: String) : State
        data class Search(
            val address: String,
            val type: Type,
        ) : State {
            enum class Type {
                WAITING,
                TO_COMING,
                TO_WAITING,
                COMING,
                STOPPING,
            }

            fun canStop(): Boolean {
                return when (type) {
                    Type.WAITING, Type.COMING -> true
                    else -> false
                }
            }
        }
        data class Connected(val address: String) : State
        data class Disconnecting(val address: String) : State
        object Disconnected : State
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private var gatt: BluetoothGatt? = null
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            Log.d(TAG, "on scan result: callback $callbackType result $result")
            when (val bleState = state.value) {
                is State.Search -> {
                    when (bleState.type) {
                        State.Search.Type.COMING -> {
                            if (result == null) return
                            val scanRecord = result.scanRecord ?: return
                            val device = result.device ?: return
                            if (device.address == bleState.address) onConnect()
                        }
                        else -> {
                            // noop
                        }
                    }
                }
                is State.Connecting -> {
                    // noop
                }
                else -> TODO("onScanResult bleState: $bleState")
            }
        }
    }
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (gatt == null) return // todo
            Log.d(TAG, "on connection state change $status $newState")
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            when (state.value) {
                                is State.Connecting -> {
                                    onConnectSuccess(gatt)
                                }
                                else -> TODO()
                            }
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            when (state.value) {
                                is State.Disconnecting -> {
                                    try {
                                        gatt.close()
                                    } catch (e: Throwable) {
                                        Log.w(TAG, "Close gatt ${gatt.device.address} error: $e")
                                    }
                                    this@BLEGattService.gatt = null
                                    _state.value = State.Disconnected
                                    stopForeground(STOP_FOREGROUND_REMOVE)
                                    unregisterReceiver(receivers)
                                }
                                is State.Connected -> {
                                    val scanStart = runCatching { isBTEnabled() }.getOrDefault(false)
                                    onDisconnectConnected(scanStart = scanStart)
                                }
                                else -> TODO()
                            }
                        }
                        else -> {
                            TODO("Gatt newState: $newState")
                        }
                    }
                }
                else -> {
                    // noop
                }
            }
        }
    }
    private val receivers = object : BroadcastReceiver() {
        private fun onReceive(intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    Log.d(TAG, "Bluetooth device state: ACTION_ACL_DISCONNECTED device ${device.address}")
                    when (val state = state.value) {
                        is State.Connected -> {
                            if (device.address != state.address) TODO()
                            onDisconnectConnected(scanStart = true)
                        }
                        else -> TODO()
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    Log.d(TAG, "Bluetooth adapter state: $state")
                    when (state) {
                        BluetoothAdapter.STATE_OFF -> {
                            when (val bleState = BLEGattService.state.value) {
                                is State.Search -> {
                                    when (bleState.type) {
                                        State.Search.Type.COMING -> {
                                            onSearchWaiting()
                                        }
                                        else -> {
                                            // todo
                                        }
                                    }
                                }
                                is State.Connected -> {
                                    onDisconnectConnected(scanStart = false)
                                }
                                else -> {
                                    // todo
                                }
                            }
                        }
                        BluetoothAdapter.STATE_ON -> {
                            when (val bleState = BLEGattService.state.value) {
                                is State.Search -> {
                                    when (bleState.type) {
                                        State.Search.Type.WAITING -> {
                                            onScanStart()
                                        }
                                        else -> {
                                            // todo
                                        }
                                    }
                                }
                                else -> {
                                    // todo
                                }
                            }
                        }
                        else -> {
                            // noop
                        }
                    }
                }
                LocationManager.PROVIDERS_CHANGED_ACTION -> {
                    val locationManager = getSystemService(LocationManager::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val name = intent.getStringExtra(LocationManager.EXTRA_PROVIDER_NAME)
                        if (name != LocationManager.GPS_PROVIDER) return
                    }
                    val isLocationEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    Log.d(TAG, "isLocationEnabled: $isLocationEnabled")
                    if (!isLocationEnabled) {
                        TODO("isLocationEnabled: $isLocationEnabled")
                    }
                }
                else -> {
                    // noop
                }
            }
        }

        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null) onReceive(intent)
        }
    }

    private fun onDisconnectConnected(scanStart: Boolean) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        val oldGatt = checkNotNull(gatt)
        try {
            oldGatt.close()
        } catch (e: Throwable) {
            Log.w(TAG, "Close gatt ${oldGatt.device.address} error: $e")
        }
        gatt = null
        _state.value = State.Search(
            address = oldGatt.device.address,
            type = State.Search.Type.WAITING,
        )
        if (scanStart) onScanStart()
    }

    private fun onScanStart() {
        Log.d(TAG, "scan start...")
        val service: Service = this
        scope.launch {
            val state = state.value
            if (state !is State.Search) TODO()
            if (state.type != State.Search.Type.WAITING) TODO()
            _state.value = State.Search(
                address = state.address,
                type = State.Search.Type.TO_COMING,
            )
            runCatching {
                withContext(Dispatchers.Default) {
                    scanStart(scanCallback)
                }
            }.fold(
                onSuccess = {
                    val intent = Intent(service, BLEGattService::class.java)
                    intent.action = ACTION_SEARCH_STOP
                    ForegroundUtil.startForeground(
                        service = service,
                        title = "searching ${state.address}...",
                        action = "stop",
                        intent = intent,
                    )
                    _state.value = State.Search(
                        address = state.address,
                        type = State.Search.Type.COMING,
                    )
                },
                onFailure = {
                    _broadcast.emit(Broadcast.OnError(it))
                    _state.value = State.Disconnected
                    unregisterReceiver(receivers)
                },
            )
        }
    }

    private fun onSearchWaiting() {
        Log.d(TAG, "search waiting...")
        val service: Service = this
        scope.launch {
            val state = state.value
            if (state !is State.Search) TODO()
            if (state.type != State.Search.Type.COMING) TODO()
            _state.value = State.Search(
                address = state.address,
                type = State.Search.Type.TO_WAITING,
            )
            runCatching {
                withContext(Dispatchers.Default) {
                    scanStop(scanCallback)
                }
            }.fold(
                onSuccess = {
                    val intent = Intent(service, BLEGattService::class.java)
                    intent.action = ACTION_SEARCH_STOP
                    ForegroundUtil.startForeground(
                        service = service,
                        title = "search waiting...",
                        action = "stop",
                        intent = intent,
                    )
                    _state.value = State.Search(
                        address = state.address,
                        type = State.Search.Type.WAITING,
                    )
                },
                onFailure = {
                    _broadcast.emit(Broadcast.OnError(it))
                    _state.value = State.Disconnected
                    unregisterReceiver(receivers)
                },
            )
        }
    }

    /*
    private fun onBTStateOn(gatt: BluetoothGatt) {
        val context: Context = this
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        scope.launch {
            runCatching {
                val state = bluetoothManager.getConnectionState(gatt.device, BluetoothProfile.GATT)
                Log.d(TAG, "on BT device state $state")
                when (state) {
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        val autoConnect = false
                        gatt.device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
                    }
                    BluetoothProfile.STATE_CONNECTED -> {
                        _connectState.value = ConnectState.CONNECTED
                    }
                    else -> {
                        // noop
                    }
                }
            }.fold(
                onSuccess = {
                            // todo
                },
                onFailure = {
                    Log.w(TAG, "BT state ON device ${gatt.device.address} error: $it")
                    _connectState.value = ConnectState.DISCONNECTED
                },
            )
        }
    }
    */

    private fun onConnectSuccess(gatt: BluetoothGatt) {
        Log.d(TAG, "connect success...")
        val state = state.value
        if (state !is State.Connecting) TODO()
        val service: Service = this
        val address = state.address
        scope.launch {
            runCatching {
                scanStop(scanCallback)
            }.fold(
                onSuccess = {
                    this@BLEGattService.gatt = gatt
                    val intent = Intent(service, BLEGattService::class.java)
                    intent.action = ACTION_DISCONNECT
                    ForegroundUtil.startForeground(
                        service = service,
                        title = "connected $address",
                        action = "disconnect",
                        intent = intent,
                    )
                    _state.value = State.Connected(address = address)
                },
                onFailure = {
                            TODO()
                },
            )
        }
    }

    private fun onConnect() {
        Log.d(TAG, "connect device...")
        val state = state.value
        if (state is State.Connecting) return
        if (state !is State.Search) TODO()
        if (state.type != State.Search.Type.COMING) TODO()
        val address = state.address
        _state.value = State.Connecting(address = address)
        val service: Service = this
        scope.launch {
            ForegroundUtil.startForeground(
                service = service,
                title = "connecting $address...",
            )
            runCatching {
                withContext(Dispatchers.Default) {
                    val autoConnect = false
                    requireBTAdapter()
                        .getRemoteDevice(address)
                        .connectGatt(service, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
                }
            }.fold(
                onSuccess = {
                            // todo
                },
                onFailure = {
                    _broadcast.emit(Broadcast.OnError(it))
                    _state.value = State.Search(
                        address = address,
                        type = State.Search.Type.WAITING,
                    )
                    onScanStart()
                },
            )
        }
    }

    private fun registerReceivers() {
        val filter = IntentFilter().also {
            it.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            it.addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            it.addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
        }
        registerReceiver(receivers, filter)
    }

    private fun onConnect(address: String) {
        Log.d(TAG, "connect $address...")
        val service: Service = this
        if (state.value != State.Disconnected) TODO("connect state: $state")
        val context: Context = this
        scope.launch {
            _state.value = State.Search(
                address = address,
                type = State.Search.Type.TO_COMING,
            )
            runCatching {
                withContext(Dispatchers.Default) {
                    scanStart(scanCallback)
                }
            }.fold(
                onSuccess = {
                    val intent = Intent(service, BLEGattService::class.java)
                    intent.action = ACTION_SEARCH_STOP
                    ForegroundUtil.startForeground(
                        service = service,
                        title = "searching $address...",
                        action = "stop",
                        intent = intent,
                    )
                    _state.value = State.Search(
                        address = address,
                        type = State.Search.Type.COMING,
                    )
                    registerReceivers()
                },
                onFailure = {
                    _broadcast.emit(Broadcast.OnError(it))
                    _state.value = State.Disconnected
                },
            )
        }
    }

    private fun onDisconnect() {
        Log.d(TAG, "disconnect...")
        val state = state.value
        if (state !is State.Connected) TODO("connect state: $state")
        val address = state.address
        val service: Service = this
        _state.value = State.Disconnecting(address = address)
        ForegroundUtil.startForeground(
            service = service,
            title = "disconnecting $address...",
        )
        scope.launch {
            runCatching {
                val gatt = checkNotNull(gatt)
                withContext(Dispatchers.Default) {
                    // todo timeout
                    gatt.disconnect()
                }
            }.fold(
                onSuccess = {
                    // todo
                },
                onFailure = {
                    _broadcast.emit(Broadcast.OnError(it))
                    _state.value = State.Disconnected
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    unregisterReceiver(receivers)
                }
            )
        }
    }

    private fun onStopSearch() {
        Log.d(TAG, "search stop...")
        val service: Service = this
        scope.launch {
            val state = state.value
            if (state !is State.Search) TODO()
            if (!state.canStop()) TODO()
            _state.value = State.Search(
                address = state.address,
                type = State.Search.Type.STOPPING,
            )
            runCatching {
                withContext(Dispatchers.Default) {
                    scanStop(scanCallback)
                }
            }.fold(
                onSuccess = {
                            // todo
                },
                onFailure = {
                    _broadcast.emit(Broadcast.OnError(it))
                },
            )
            _state.value = State.Disconnected
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun onStartCommand(intent: Intent) {
        when (intent.action) {
            ACTION_CONNECT -> onConnect(address = intent.getStringExtra("address")!!)
            ACTION_SEARCH_STOP -> onStopSearch()
            ACTION_DISCONNECT -> onDisconnect()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) onStartCommand(intent)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    companion object {
        private const val TAG = "[Gatt]"
        private val ACTION_CONNECT = "${this::class.java.name}:ACTION_CONNECT"
        private val ACTION_DISCONNECT = "${this::class.java.name}:ACTION_DISCONNECT"
        private val ACTION_SEARCH_STOP = "${this::class.java.name}:ACTION_SEARCH_STOP"

        private val _broadcast = MutableSharedFlow<Broadcast>()
        val broadcast = _broadcast.asSharedFlow()
        private val _state = MutableStateFlow<State>(State.Disconnected)
        val state = _state.asStateFlow()

        fun connect(context: Context, address: String) {
            val intent = Intent(context, BLEGattService::class.java)
            intent.action = ACTION_CONNECT
            intent.putExtra("address", address)
            context.startService(intent)
        }

        fun disconnect(context: Context) {
            val intent = Intent(context, BLEGattService::class.java)
            intent.action = ACTION_DISCONNECT
            context.startService(intent)
        }

        fun searchStop(context: Context) {
            val intent = Intent(context, BLEGattService::class.java)
            intent.action = ACTION_SEARCH_STOP
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "on create[${hashCode()}]...") // todo
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "on destroy...")
        job.cancel()
    }
}
