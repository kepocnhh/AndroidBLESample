package test.android.ble.module.bluetooth

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
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
import androidx.lifecycle.flowWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import test.android.ble.util.ForegroundUtil
import test.android.ble.util.android.BTException
import test.android.ble.util.android.LocException
import test.android.ble.util.android.isBTEnabled
import test.android.ble.util.android.isLocationEnabled
import test.android.ble.util.android.requireBTAdapter
import test.android.ble.util.android.scanStart
import test.android.ble.util.android.scanStop
import java.util.UUID

internal class BLEGattService : Service() {
    sealed interface Broadcast {
        class OnError(val error: Throwable) : Broadcast
        class OnWrite(
            val service: UUID,
            val characteristic: UUID,
            val bytes: ByteArray,
        ) : Broadcast
    }

    sealed interface State {
        data class Connecting(
            val address: String,
            val type: Type,
        ) : State {
            enum class Type {
                CONNECTS,
                DISCOVER,
            }
        }
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
        data class Connected(
            val address: String,
            val isPaired: Boolean,
            val type: Type,
            val services: Map<UUID, Set<UUID>>,
        ) : State {
            enum class Type {
                READY,
                WRITING,
                PAIRING,
            }
        }
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
                is State.Connected -> {
                    if (bleState.address != result?.device?.address) {
                        TODO("Expected ${bleState.address}\nActual ${result?.device?.address}")
                    }
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
                            val state = state.value
                            Log.w(TAG, "GATT disconnected! $state")
                            when (state) {
                                is State.Disconnecting -> {
                                    disconnect()
                                }
                                is State.Connected -> {
                                    onDisconnectToSearch()
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

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int,
        ) {
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    if (gatt == null) TODO()
                    if (characteristic == null) TODO()
                    Log.d(TAG, "On characteristic ${characteristic.service.uuid}/${characteristic.uuid} write success.")
                    onCharacteristicWrite(characteristic)
                }
                else -> {
                    if (characteristic == null) {
                        Log.w(TAG, "On characteristic write $status!")
                    } else {
                        Log.w(TAG, "On characteristic ${characteristic.service.uuid}/${characteristic.uuid} write $status!")
                    }
                    // todo
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    if (gatt == null) TODO()
                    Log.d(TAG, "On services discovered success.")
                    onServicesDiscovered(gatt)
                }
                else -> {
                    Log.w(TAG, "On services discovered $status!")
                    // todo
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
                    onBTDeviceDisconnected(device)
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    Log.d(TAG, "Bluetooth adapter state: $state")
                    when (state) {
                        BluetoothAdapter.STATE_TURNING_OFF -> {
                            Log.d(TAG, "BT turning off...")
                        }
                        BluetoothAdapter.STATE_OFF -> {
                            onBTOff()
                        }
                        BluetoothAdapter.STATE_ON -> {
                            val bleState = BLEGattService.state.value
                            Log.d(TAG, "BLE state: $bleState")
                            when (bleState) {
                                is State.Search -> {
                                    when (bleState.type) {
                                        State.Search.Type.WAITING -> {
                                            fromWaiting()
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
                    val provider = LocationManager.GPS_PROVIDER
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val name = intent.getStringExtra(LocationManager.EXTRA_PROVIDER_NAME)
                        if (name != provider) return
                    }
                    if (isLocationEnabled(provider = provider)) {
                        Log.d(TAG, "Location enabled.")
                        when (val bleState = state.value) {
                            is State.Search -> {
                                when (bleState.type) {
                                    State.Search.Type.WAITING -> {
                                        fromWaiting()
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
                    } else {
                        Log.w(TAG, "Location disabled!")
                        when (val bleState = state.value) {
                            is State.Search -> {
                                when (bleState.type) {
                                    State.Search.Type.COMING -> {
                                        toWaiting()
                                    }
                                    else -> {
                                        // todo
                                    }
                                }
                            }
                            is State.Connected -> {
                                onDisconnectToSearch()
                            }
                            else -> {
                                // noop
                            }
                        }
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
    private val receiversConnected = object : BroadcastReceiver() {
        private fun onReceive(intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    TODO()
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    Log.d(TAG, "Bluetooth device state: ACTION_ACL_DISCONNECTED device ${device.address}")
                    onBTDeviceDisconnected(device)
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

    private fun onCharacteristicWrite(characteristic: BluetoothGattCharacteristic) {
        val state = state.value
        if (state !is State.Connected) TODO()
        if (state.type != State.Connected.Type.WRITING) TODO()
        _state.value = state.copy(type = State.Connected.Type.READY)
        scope.launch {
            _broadcast.emit(
                Broadcast.OnWrite(
                    service = characteristic.service.uuid,
                    characteristic = characteristic.uuid,
                    bytes = characteristic.value,
                ),
            )
        }
    }

    private fun onServicesDiscovered(gatt: BluetoothGatt) {
        val state = state.value
        if (state !is State.Connecting) TODO()
        if (state.type != State.Connecting.Type.DISCOVER) TODO()
        val service = this
        this.gatt = gatt
        val intent = Intent(service, BLEGattService::class.java)
        intent.action = ACTION_DISCONNECT
        ForegroundUtil.startForeground(
            service = service,
            title = "connected ${state.address}",
            action = "disconnect",
            intent = intent,
        )
        _state.value = State.Connected(
            address = state.address,
            isPaired = gatt.device.bondState == BluetoothDevice.BOND_BONDED,
            type = State.Connected.Type.READY,
            services = gatt.services.associate { gs ->
                gs.uuid to gs.characteristics.map { it.uuid }.toSet()
            },
        )
    }

    private fun onBTOff() {
        val state = state.value
        Log.w(TAG, "BT off! state: $state")
        when (state) {
            is State.Search -> {
                when (state.type) {
                    State.Search.Type.COMING -> {
                        toWaiting()
                    }
                    else -> {
                        // todo
                    }
                }
            }
            is State.Connected -> {
                onDisconnectToSearch()
            }
            else -> {
                // todo
            }
        }
    }

    private fun onBTDeviceDisconnected(device: BluetoothDevice) {
        when (val state = state.value) {
            is State.Connected -> {
                if (device.address != state.address) TODO()
                onDisconnectToSearch()
            }
            is State.Search -> {
                // noop
            }
            else -> TODO("BT device ${device.address} disconnected state $state")
        }
    }

    private fun disconnect() {
        _state.value = State.Disconnected
        val oldGatt = checkNotNull(gatt)
        try {
            oldGatt.close()
        } catch (e: Throwable) {
            Log.w(TAG, "Close gatt ${oldGatt.device.address} error: $e")
        }
        gatt = null
    }

    private fun onDisconnectToSearch() {
        Log.d(TAG, "on disconnect to search...")
        val state = state.value
        if (state !is State.Connected) TODO("state: $state")
        _state.value = State.Search(
            address = state.address,
            type = State.Search.Type.WAITING,
        )
        val oldGatt = checkNotNull(gatt)
        try {
            oldGatt.close()
        } catch (e: Throwable) {
            Log.w(TAG, "Close gatt ${oldGatt.device.address} error: $e")
        }
        gatt = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        fromWaiting()
    }

    private suspend fun toComing() {
        val state = state.value
        if (state !is State.Search) TODO()
        if (state.type != State.Search.Type.WAITING) TODO()
        val service: Service = this
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
            },
        )
    }

    private fun fromWaiting() {
        Log.d(TAG, "from waiting...")
        scope.launch {
            val state = state.value
            if (state !is State.Search) TODO()
            if (state.type != State.Search.Type.WAITING) TODO()
            if (runCatching { isBTEnabled() }.getOrDefault(false) && isLocationEnabled()) {
                toComing()
            } else {
                startForegroundWaiting()
            }
        }
    }

    private fun startForegroundWaiting() {
        val intent = Intent(this, BLEGattService::class.java)
        intent.action = ACTION_SEARCH_STOP
        ForegroundUtil.startForeground(
            service = this,
            title = "search waiting...",
            action = "stop",
            intent = intent,
        )
    }

    private fun toWaiting() {
        Log.d(TAG, "to waiting...")
        val state = state.value
        if (state !is State.Search) TODO()
        if (state.type != State.Search.Type.COMING) TODO()
        _state.value = State.Search(
            address = state.address,
            type = State.Search.Type.TO_WAITING,
        )
        scope.launch {
            runCatching {
                withContext(Dispatchers.Default) {
                    scanStop(scanCallback)
                }
            }.fold(
                onSuccess = {
                    _state.value = State.Search(
                        address = state.address,
                        type = State.Search.Type.WAITING,
                    )
                    startForegroundWaiting()
                },
                onFailure = {
                    _broadcast.emit(Broadcast.OnError(it))
                    _state.value = State.Disconnected
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
        if (state.type != State.Connecting.Type.CONNECTS) TODO()
        scope.launch {
            runCatching {
                scanStop(scanCallback)
                gatt.discoverServices()
            }.fold(
                onSuccess = {
                    _state.value = state.copy(type = State.Connecting.Type.DISCOVER)
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
        _state.value = State.Connecting(address = address, type = State.Connecting.Type.CONNECTS)
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
                    fromWaiting()
                },
            )
        }
    }

    private fun onConnect(address: String) {
        Log.d(TAG, "connect $address...")
        if (state.value != State.Disconnected) TODO("connect state: $state")
        scope.launch {
            runCatching {
                withContext(Dispatchers.Default) {
                    if (!isBTEnabled()) throw BTException(BTException.Error.DISABLED)
                    if (!isLocationEnabled()) throw LocException(LocException.Error.DISABLED)
                }
            }.fold(
                onSuccess = {
                    _state.value = State.Search(
                        address = address,
                        type = State.Search.Type.WAITING,
                    )
                    startForegroundWaiting()
                    toComing()
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
        }
    }

    private fun writeCharacteristic(
        gatt: BluetoothGatt,
        service: UUID,
        characteristic: UUID,
        bytes: ByteArray,
    ) {
        val service = gatt.getService(service)
            ?: TODO("No service $service!")
        val characteristic = service.getCharacteristic(characteristic)
            ?: TODO("No characteristic $characteristic!")
//        val status = gatt.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        characteristic.value = bytes
        val success = gatt.writeCharacteristic(characteristic)
        if (!success) {
            TODO("GATT write error!")
        }
    }

    private fun onWriteCharacteristic(
        service: UUID,
        characteristic: UUID,
        bytes: ByteArray,
    ) {
        Log.d(TAG, "on write characteristic ${bytes.map { String.format("%03d", it.toInt() and 0xFF) }}...")
        val state = state.value
        if (state !is State.Connected) TODO("connect state: $state")
        _state.value = state.copy(type = State.Connected.Type.WRITING)
        scope.launch {
            runCatching {
                withContext(Dispatchers.Default) {
                    writeCharacteristic(
                        gatt = checkNotNull(gatt),
                        service = service,
                        characteristic = characteristic,
                        bytes = bytes,
                    )
                }
            }.fold(
                onSuccess = {
                    // todo
                },
                onFailure = {
                    TODO("GATT write error: $it!")
                },
            )
        }
    }

    private fun onPair() {
        Log.d(TAG, "on pair...")
        val state = state.value
        if (state !is State.Connected) TODO("pair state: $state")
        if (state.type != State.Connected.Type.READY) TODO("pair state.type: ${state.type}")
        if (state.isPaired) TODO("Already paired!")
        _state.value = state.copy(type = State.Connected.Type.PAIRING)
        scope.launch {
            runCatching {
                withContext(Dispatchers.Default) {
                    TODO()
                }
            }.fold(
                onSuccess = {
                    // todo
                },
                onFailure = {
                    TODO("GATT pair error: $it!")
                },
            )
        }
    }

    private fun onStartCommand(intent: Intent) {
        when (intent.action) {
            ACTION_CONNECT -> onConnect(address = intent.getStringExtra("address")!!)
            ACTION_SEARCH_STOP -> onStopSearch()
            ACTION_DISCONNECT -> onDisconnect()
            ACTION_WRITE_CHARACTERISTIC -> {
                val bytes = intent.getByteArrayExtra("bytes") ?: TODO("No bytes!")
                val service = intent.getStringExtra("service")
                    ?.let(UUID::fromString)
                    ?: TODO("No service!")
                val characteristic = intent.getStringExtra("characteristic")
                    ?.let(UUID::fromString)
                    ?: TODO("No characteristic!")
                onWriteCharacteristic(
                    service = service,
                    characteristic = characteristic,
                    bytes = bytes,
                )
            }
            ACTION_PAIR -> onPair()
            else -> TODO("Unknown action: ${intent.action}!")
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
        private val ACTION_WRITE_CHARACTERISTIC = "${this::class.java.name}:ACTION_WRITE_CHARACTERISTIC"
        private val ACTION_PAIR = "${this::class.java.name}:ACTION_PAIR"

        private val _broadcast = MutableSharedFlow<Broadcast>()
        @JvmStatic
        val broadcast = _broadcast.asSharedFlow()
        private var oldState: State = State.Disconnected
        private val _state = MutableStateFlow<State>(State.Disconnected)
        @JvmStatic
        val state = _state.asStateFlow()

        @JvmStatic
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

        fun writeCharacteristic(
            context: Context,
            service: UUID,
            characteristic: UUID,
            bytes: ByteArray,
        ) {
            val intent = Intent(context, BLEGattService::class.java)
            intent.action = ACTION_WRITE_CHARACTERISTIC
            intent.putExtra("service", service.toString())
            intent.putExtra("characteristic", characteristic.toString())
            intent.putExtra("bytes", bytes)
            context.startService(intent)
        }

        fun pair(context: Context) {
            val intent = Intent(context, BLEGattService::class.java)
            intent.action = ACTION_PAIR
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "on create[${hashCode()}]...") // todo
        state
            .onEach { newState ->
                onNewState(oldState, newState)
                oldState = newState
            }
            .launchIn(scope)
    }

    private fun onNewState(oldState: State, newState: State) {
        if (oldState is State.Connected) {
            if (newState !is State.Connected) {
                unregisterReceiver(receiversConnected)
            }
        } else {
            if (newState is State.Connected) {
                val filter = IntentFilter().also {
                    it.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                    it.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                }
                registerReceiver(receiversConnected, filter)
            }
        }
        if (oldState is State.Disconnected) {
            if (newState !is State.Disconnected) {
                val filter = IntentFilter().also {
                    it.addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                    it.addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
                }
                registerReceiver(receivers, filter)
            }
        } else {
            if (newState is State.Disconnected) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                unregisterReceiver(receivers)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "on destroy...")
        job.cancel()
    }
}
