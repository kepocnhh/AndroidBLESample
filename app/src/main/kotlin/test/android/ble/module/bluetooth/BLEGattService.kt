package test.android.ble.module.bluetooth

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Parcelable
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import test.android.ble.entity.BTDevice
import test.android.ble.util.ForegroundUtil
import test.android.ble.util.android.BTException
import test.android.ble.util.android.GattException
import test.android.ble.util.android.GattUtil
import test.android.ble.util.android.LocException
import test.android.ble.util.android.PairException
import test.android.ble.util.android.checkPIN
import test.android.ble.util.android.isBTEnabled
import test.android.ble.util.android.isLocationEnabled
import test.android.ble.util.android.removeBond
import test.android.ble.util.android.requireBTAdapter
import java.util.Queue
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal class BLEGattService : Service() {
    sealed interface Broadcast {
        class OnError(val error: Throwable) : Broadcast
        class OnPair(val result: Result<BTDevice>) : Broadcast
        object OnDisconnect : Broadcast
        class OnConnect(val state: State.Connected) : Broadcast
    }

    sealed interface State {
        data class Connecting(
            val address: String,
            @Deprecated("Connected.DISCOVER")
            val type: Type,
        ) : State {
            enum class Type {
                CONNECTS,
                @Deprecated("Connected.DISCOVER")
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
            @Deprecated("Connected.DISCOVER")
            val services: Map<UUID, Map<UUID, Set<UUID>>>,
        ) : State {
            enum class Type {
                READY,
                OPERATING,
                PAIRING,
                UNPAIRING,
                DISCOVER,
            }
        }
        data class Disconnecting(val address: String) : State
        object Disconnected : State
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private var gatt: BluetoothGatt? = null
    private var pin: String? = null
    private var scanSettings: ScanSettings? = null
    private val scanner = BLEScanner(
        context = this,
        scope = scope,
        onScanResult = ::onScanResult,
        timeoutUntil = {
            state.value is State.Search
        },
    )

    private fun onScanResult(result: ScanResult) {
        when (val state = state.value) {
            is State.Search -> {
                when (state.type) {
                    State.Search.Type.COMING -> {
                        val device = result.device ?: TODO("State: $state. But no device!")
                        if (device.address == state.address) {
                            try {
                                scanner.stop()
                            } catch (e: Throwable) {
                                // todo
                            }
                            onConnect()
                        }
                    }
                    else -> {
                        // noop
                    }
                }
            }
            else -> TODO("This callback should only operate in the searching state. But state is $state!")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            Log.d(TAG, "on connection state change $status $newState")
            val GATT_ERROR = 133 // https://stackoverflow.com/a/60849590
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            when (val state = state.value) {
                                is State.Connecting -> {
                                    if (gatt == null) TODO("State: $state. Connection state is connected. But no GATT!")
                                    onConnectSuccess(gatt)
                                }
                                else -> TODO("State: $state. But GATT connected!")
                            }
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            when (val state = state.value) {
                                is State.Disconnecting -> {
                                    Log.d(TAG, "GATT disconnected.")
                                    disconnect()
                                }
                                is State.Connected -> {
                                    Log.w(TAG, "GATT disconnected from connected state!")
                                    onDisconnectToSearch()
                                }
                                is State.Connecting -> {
                                    Log.w(TAG, "GATT disconnected from connecting state!")
                                    onDisconnectToSearch()
                                }
                                else -> TODO("State: $state. But GATT disconnected!")
                            }
                        }
                        else -> {
                            TODO("Gatt newState: $newState")
                        }
                    }
                }
                GATT_ERROR -> {
                    when (newState) {
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            Log.w(TAG, "GATT error!")
                            when (val state = state.value) {
                                is State.Connecting -> {
                                    onDisconnectToSearch()
                                }
                                else -> TODO("GATT error! state: $state")
                            }
                        }
                        else -> TODO("GATT error! $newState")
                    }
                }
                else -> {
                    // noop
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    Log.d(TAG, "On characteristic ${characteristic.service.uuid}/${characteristic.uuid} read success.")
                    onCharacteristicRead(characteristic)
                }
                else -> {
                    Log.w(TAG, "On characteristic ${characteristic.service.uuid}/${characteristic.uuid} read $status!")
                    // todo
                }
            }
        }

        @Deprecated(message = "api 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
        ) {
            if (gatt == null) return
            if (characteristic == null) return
            Log.i(TAG, "On characteristic DEPRECATED ${characteristic.service.uuid}/${characteristic.uuid} changed.")
            onCharacteristicChanged(characteristic, value = characteristic.value.copyOf())
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
                    when (val state = state.value) {
                        is State.Connected -> {
                            if (state.type != State.Connected.Type.DISCOVER) TODO("On services discovered Connected type: ${state.type}")
                            onServicesDiscoveredConnected()
                        }
                        is State.Connecting -> {
                            if (state.type != State.Connecting.Type.DISCOVER) TODO("On services discovered Connecting type: ${state.type}")
                            onServicesDiscovered(gatt)
                        }
                        else -> TODO("")
                    }
                }
                else -> {
                    Log.w(TAG, "On services discovered $status!")
                    // todo
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            Log.d(TAG, "On characteristic ${characteristic.service.uuid}/${characteristic.uuid} changed.")
            onCharacteristicChanged(characteristic, value = value)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    if (gatt == null) TODO("No gatt!")
                    if (descriptor == null) TODO("No descriptor!")
                    Log.i(TAG, "On descriptor ${descriptor.characteristic.service.uuid}/${descriptor.characteristic.uuid}/${descriptor.uuid} write success.")
                    onDescriptorWrite(descriptor)
                }
                else -> {
                    if (descriptor == null) {
                        Log.w(TAG, "On descriptor write $status!")
                    } else {
                        Log.w(TAG, "On descriptor ${descriptor.characteristic.service.uuid}/${descriptor.characteristic.uuid}/${descriptor.uuid} write $status!")
                    }
                    // todo
                }
            }
        }
    }
    private val receivers = object : BroadcastReceiver() {
        private fun onReceive(intent: Intent) {
            when (intent.action) {
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
                            is State.Connecting -> {
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
    private val receiversPairing = object : BroadcastReceiver() {
        private fun onReceive(intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                    val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR)
                    when (variant) {
                        BluetoothDevice.PAIRING_VARIANT_PIN -> {
                            val state = state.value
                            if (state !is State.Connected) return
                            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                                ?: TODO("ACTION_PAIRING_REQUEST/PAIRING_VARIANT_PIN: No device!")
                            if (state.address != device.address) return
                            val pin = pin ?: return
                            if (state.type != State.Connected.Type.PAIRING) TODO("ACTION_PAIRING_REQUEST/PAIRING_VARIANT_PIN: state type: ${state.type}!")
                            abortBroadcast()
                            this@BLEGattService.pin = null
                            onPINPairingRequest(address = device.address, pin = pin)
                        }
                    }
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
                    val oldState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                    val newState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        ?: TODO("No device!")
                    val state = state.value
                    if (state !is State.Connected) TODO("This receiver should only operate in the connected state. But state is $state!")
                    when (state.type) {
                        State.Connected.Type.PAIRING, State.Connected.Type.UNPAIRING -> {
                            // noop
                        }
                        else -> {
                            if (device.address == state.address) {
                                TODO("State: $state. Type: ${state.type}. The service is not ready for pairing/unpairing device ${state.address}!")
                            }
                            Log.d(TAG, "State: $state. Type: ${state.type}. So I ignore device ${device.address}.")
                            return
                        }
                    }
                    if (device.address != state.address) {
                        Log.d(TAG, "Expected device ${state.address}. So I ignore device ${device.address}.")
                        return
                    }
                    Log.d(TAG, "Bond state changed: $oldState -> $newState")
                    when (newState) {
                        BluetoothDevice.BOND_BONDED -> {
                            onBonded(device)
                        }
                        BluetoothDevice.BOND_BONDING -> {
                            Log.d(TAG, "bonding...")
                        }
                        BluetoothDevice.BOND_NONE -> {
                            when (oldState) {
                                BluetoothDevice.BOND_BONDING -> {
                                    pin = null
                                    val extras = intent.extras ?: TODO("No extras!")
                                    val reasonKey = "android.bluetooth.device.extra.REASON"
                                    if (extras.containsKey(reasonKey)) {
                                        val reason = intent.getIntExtra(reasonKey, BluetoothDevice.ERROR)
                                        val UNBOND_REASON_AUTH_FAILED = 1
                                        val UNBOND_REASON_AUTH_REJECTED = 2
                                        val UNBOND_REASON_AUTH_CANCELED = 3
                                        val UNBOND_REASON_REMOVED = 9
                                        val error = when (reason) {
                                            UNBOND_REASON_AUTH_FAILED -> {
                                                Log.w(TAG, "No pairing because auth failed!")
                                                PairException.Error.FAILED
                                            }
                                            UNBOND_REASON_AUTH_REJECTED -> {
                                                Log.w(TAG, "No pairing because auth rejected!")
                                                PairException.Error.REJECTED
                                            }
                                            UNBOND_REASON_AUTH_CANCELED -> {
                                                Log.w(TAG, "No pairing because auth canceled!")
                                                PairException.Error.CANCELED
                                            }
                                            UNBOND_REASON_REMOVED -> {
                                                Log.w(TAG, "No pairing because removed!")
                                                null // todo
                                            }
                                            else -> {
                                                Log.w(TAG, "No pairing because unknown error! Code: $reason")
                                                null
                                            }
                                        }
                                        onBondingFailed(address = device.address, error)
                                    } else {
                                        Log.w(TAG, "No pairing with unknown reason!")
                                        onBondingFailed(address = device.address, null)
                                    }
                                }
                                else -> TODO("State $oldState -> $newState unsupported!")
                            }
                        }
                        else -> TODO("State $newState unsupported!")
                    }
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

    private fun <T> launchCatching(
        block: suspend () -> T,
        onSuccess: suspend (T) -> Unit,
        onFailure: suspend (Throwable) -> Unit,
    ) {
        scope.launch {
            runCatching {
                block()
            }.fold(
                onSuccess = {
                    onSuccess(it)
                },
                onFailure = {
                    onFailure(it)
                }
            )
        }
    }

    private fun onBondingFailed(address: String, error: PairException.Error?) {
        val state = state.value
        if (state !is State.Connected) TODO("State: $state!")
        if (state.type != State.Connected.Type.PAIRING) TODO("State type: ${state.type}!")
        if (address != state.address) TODO("Expected ${state.address}, actual $address!")
        _state.value = state.copy(type = State.Connected.Type.READY, isPaired = false)
        scope.launch {
            _broadcast.emit(
                Broadcast.OnPair(
                    Result.failure(PairException(error)),
                ),
            )
        }
    }

    private fun onBonded(device: BluetoothDevice) {
        val state = state.value
        if (state !is State.Connected) TODO("State: $state!")
        if (state.type != State.Connected.Type.PAIRING) TODO("State type: ${state.type}!")
        if (device.address != state.address) TODO("Expected ${state.address}, actual ${device.address}!")
        _state.value = state.copy(type = State.Connected.Type.READY, isPaired = true)
        scope.launch {
            _broadcast.emit(
                Broadcast.OnPair(
                    Result.success(
                        BTDevice(
                            address = device.address ?: TODO("No address!"),
                            name = device.name ?: TODO("No name!"),
                        ),
                    ),
                ),
            )
        }
    }

    private fun onPINPairingRequest(address: String, pin: String) {
        val state = state.value
        if (state !is State.Connected) TODO("State: $state!")
        if (state.type != State.Connected.Type.PAIRING) TODO("State type: ${state.type}!")
        if (state.address != address) TODO("Expected ${state.address}, actual $address!")
        Log.d(TAG, "PIN pairing request: $address")
        scope.launch {
            runCatching {
                val device = requireBTAdapter()
                    .getRemoteDevice(address)
                    ?: TODO("No device $address!")
                if (!device.setPin(pin.toByteArray())) TODO("Set pin error!")
            }.fold(
                onSuccess = {
                    // noop
                },
                onFailure = {
                    Log.w(TAG, "PIN pairing request error: $it")
                    onBondingFailed(address, null)
                    onDisconnectToSearch()
                },
            )
        }
    }

    private fun onCharacteristicChanged(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        val state = state.value
        if (state !is State.Connected) {
            Log.d(TAG, "The changes are no longer relevant. Disconnected.")
            return
        }
        scope.launch {
            _profileBroadcast.emit(
                Profile.Broadcast.OnChangeCharacteristic(
                    service = characteristic.service.uuid,
                    characteristic = characteristic.uuid,
                    bytes = value,
                ),
            )
        }
    }

    private fun onCharacteristicRead(characteristic: BluetoothGattCharacteristic) {
        val state = state.value
        if (state !is State.Connected) TODO("On characteristic read state: $state")
        if (state.type != State.Connected.Type.OPERATING) TODO("On characteristic read state type: ${state.type}")
        performOperations()
        scope.launch {
            _profileBroadcast.emit(
                Profile.Broadcast.OnReadCharacteristic(
                    service = characteristic.service.uuid,
                    characteristic = characteristic.uuid,
                    bytes = characteristic.value,
                ),
            )
        }
    }

    private fun onCharacteristicWrite(characteristic: BluetoothGattCharacteristic) {
        val state = state.value
        if (state !is State.Connected) {
            Log.d(TAG, "The writing results are no longer relevant. Disconnected.")
            return
        }
        if (state.type != State.Connected.Type.OPERATING) TODO("on characteristic write state type: ${state.type}")
        performOperations()
        scope.launch {
            _profileBroadcast.emit(
                Profile.Broadcast.OnWriteCharacteristic(
                    service = characteristic.service.uuid,
                    characteristic = characteristic.uuid,
                    bytes = characteristic.value,
                ),
            )
        }
    }

    private fun onDescriptorWrite(descriptor: BluetoothGattDescriptor) {
        val state = state.value
        if (state !is State.Connected) TODO("On descriptor write state: $state")
        if (state.type != State.Connected.Type.OPERATING) TODO("On descriptor write state type: ${state.type}")
        _state.value = state.copy(type = State.Connected.Type.READY)
        scope.launch {
            _profileBroadcast.emit(
                Profile.Broadcast.OnWriteDescriptor(
                    service = descriptor.characteristic.service.uuid,
                    characteristic = descriptor.characteristic.uuid,
                    descriptor = descriptor.uuid,
                    bytes = descriptor.value,
                ),
            )
        }
    }

    private fun onServicesDiscoveredConnected() {
        val state = state.value
        if (state !is State.Connected) TODO("On services discovered Connected state: $state")
        if (state.type != State.Connected.Type.DISCOVER) TODO("On services discovered Connected state type: ${state.type}")
        val gatt = gatt ?: TODO("No GATT!")
        _state.value = State.Connected(
            address = state.address,
            isPaired = gatt.device.bondState == BluetoothDevice.BOND_BONDED,
            type = State.Connected.Type.READY,
            services = gatt.services.associate { gs ->
                gs.uuid to gs.characteristics.associate { gc ->
                    gc.uuid to gc.descriptors.map { it.uuid }.toSet()
                }
            },
        )
        scope.launch {
            _profileBroadcast.emit(
                Profile.Broadcast.OnServicesDiscovered(
                    gatt.services,
                ),
            )
        }
    }

    private fun onServicesDiscovered(gatt: BluetoothGatt) {
        val state = state.value
        if (state !is State.Connecting) TODO()
        if (state.type != State.Connecting.Type.DISCOVER) TODO()
        val service = this
        ForegroundUtil.startForeground(
            service = service,
            title = "connected ${state.address}",
            action = "disconnect",
            intent = intent(service, Action.DISCONNECT),
        )
        _state.value = State.Connected(
            address = state.address,
            isPaired = gatt.device.bondState == BluetoothDevice.BOND_BONDED,
            type = State.Connected.Type.READY,
            services = gatt.services.associate { gs ->
                gs.uuid to gs.characteristics.associate { gc ->
                    gc.uuid to gc.descriptors.map { it.uuid }.toSet()
                }
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
            is State.Connecting -> {
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
                if (device.address != state.address) {
                    Log.d(TAG, "Device ${device.address} ignored.")
                    return
                }
                onDisconnectToSearch()
            }
            is State.Search -> {
                // noop
            }
            else -> TODO("BT device ${device.address} disconnected state $state")
        }
    }

    private fun disconnect() {
        val state = state.value
        if (state !is State.Disconnecting) TODO("state: $state")
        _state.value = State.Disconnected
        val oldGatt = gatt ?: TODO("State: $state. But no GATT!")
        try {
            oldGatt.close()
        } catch (e: Throwable) {
            Log.w(TAG, "Close gatt ${state.address} error: $e")
        }
        gatt = null
    }

    private fun onDisconnectToSearch() {
        Log.d(TAG, "on disconnect to search...")
        val address = when (val state = state.value) {
            is State.Connected -> state.address
            is State.Connecting -> state.address
            else -> TODO("on disconnect to search state: $state")
        }
        _state.value = State.Search(
            address = address,
            type = State.Search.Type.WAITING,
        )
        val oldGatt = gatt ?: TODO("State: $state. But no GATT!")
        try {
            oldGatt.close()
        } catch (e: Throwable) {
            Log.w(TAG, "Close gatt $address error: $e")
        }
        gatt = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        fromWaiting()
    }

    private suspend fun toComing() {
        val state = state.value
        if (state !is State.Search) TODO()
        if (state.type != State.Search.Type.WAITING) TODO()
        val scanSettings = scanSettings ?: TODO()
        val service: Service = this
        _state.value = State.Search(
            address = state.address,
            type = State.Search.Type.TO_COMING,
        )
        runCatching {
            withContext(Dispatchers.Default) {
                scanner.start(scanSettings)
            }
        }.fold(
            onSuccess = {
                ForegroundUtil.startForeground(
                    service = service,
                    title = "searching ${state.address}...",
                    action = "stop",
                    intent = intent(service, Action.SEARCH_STOP),
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
        ForegroundUtil.startForeground(
            service = this,
            title = "search waiting...",
            action = "stop",
            intent = intent(this, Action.SEARCH_STOP),
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
                    try {
                        scanner.stop()
                    } catch (e: BTException) {
                        Log.d(TAG, "scanner stop BT error: $e")
                    }
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

    private fun onConnectSuccess(gatt: BluetoothGatt) {
        Log.d(TAG, "connect success...")
        val state = state.value
        if (state !is State.Connecting) TODO()
        if (state.type != State.Connecting.Type.CONNECTS) TODO()
        scope.launch {
            runCatching {
                scanner.stop()
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
        ForegroundUtil.startForeground(
            service = service,
            title = "connecting $address...",
        )
        launchCatching(
            block = {
                withContext(Dispatchers.Default) {
                    val autoConnect = false
                    val transport = BluetoothDevice.TRANSPORT_LE
                    requireBTAdapter()
                        .getRemoteDevice(address)
                        .connectGatt(service, autoConnect, gattCallback, transport)
                }
            },
            onSuccess = { newGatt ->
                gatt = newGatt
                val timeMax = 10.seconds
                val timeDelay = 100.milliseconds
                val timeStart = System.currentTimeMillis().milliseconds
                withContext(Dispatchers.Default) {
                    while (BLEGattService.state.value is State.Connecting) {
                        val timeNow = System.currentTimeMillis().milliseconds
                        val diff = timeNow - timeStart
                        if (diff > timeMax) {
                            Log.w(TAG, "$diff have passed since the connection started. So lets switch off.")
                            _broadcast.emit(Broadcast.OnError(GattException(GattException.Type.DISCONNECTING_BY_TIMEOUT)))
                            onDisconnectToSearch()
                            break
                        }
                        val timeLeft = timeMax - diff
                        if (timeLeft.inWholeMilliseconds - timeLeft.inWholeSeconds * 1_000 < timeDelay.inWholeMilliseconds) {
                            if (timeLeft.inWholeSeconds > 0) {
                                Log.i(TAG, "Connect to $address. ${timeLeft.inWholeSeconds} seconds left...")
                            }
                        }
                        delay(timeDelay)
                    }
                }
            },
            onFailure = {
                Log.w(TAG, "on connect GATT $address error: $it")
                _broadcast.emit(Broadcast.OnError(it))
                _state.value = State.Search(
                    address = address,
                    type = State.Search.Type.WAITING,
                )
                fromWaiting()
            },
        )
    }

    private fun onConnect(address: String, scanSettings: ScanSettings) {
        Log.d(TAG, "connect $address...")
        if (state.value != State.Disconnected) TODO("connect state: $state")
        this.scanSettings = scanSettings
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
                        type = State.Search.Type.COMING,
                    )
                    onConnect()
                },
                onFailure = {
                    _broadcast.emit(Broadcast.OnError(it))
                    _state.value = State.Disconnected
                },
            )
        }
    }

    private fun onDisconnect() {
        val state = state.value
        if (state !is State.Connected) {
            Log.d(TAG, "There is nothing left to disconnect.")
            return
        }
        Log.d(TAG, "disconnect...")
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
        val state = state.value
        if (state !is State.Search) TODO()
        if (!state.canStop()) TODO()
        _state.value = State.Search(
            address = state.address,
            type = State.Search.Type.STOPPING,
        )
        scope.launch {
            runCatching {
                withContext(Dispatchers.Default) {
                    scanner.stop()
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

    private val profileOperations: Queue<ProfileOperation> = ConcurrentLinkedQueue() // todo type
    private fun performOperations() {
        val state = state.value
        if (state !is State.Connected) TODO("perform operations state: $state")
        val operation = profileOperations.poll()
        if (operation == null) {
            when (state.type) {
                State.Connected.Type.READY -> {
                    Log.d(TAG, "All operations already performed.")
                    return
                }
                State.Connected.Type.OPERATING -> {
                    _state.value = state.copy(type = State.Connected.Type.READY)
                    Log.d(TAG, "All operations performed.")
                    return
                }
                else -> {
                    TODO("State: $state. Type: ${state.type}. But no operation!")
                }
            }
        }
        when (state.type) {
            State.Connected.Type.READY -> {
                Log.d(TAG, "Start perform operations...")
                _state.value = state.copy(type = State.Connected.Type.OPERATING)
            }
            State.Connected.Type.OPERATING -> {
                // noop
            }
            else -> TODO("perform operations state type: ${state.type}")
        }
        when (operation) {
            is ProfileOperation.WriteCharacteristic -> {
                writeCharacteristic(
                    service = operation.service,
                    characteristic = operation.characteristic,
                    bytes = operation.bytes,
                )
            }
            is ProfileOperation.WriteDescriptor -> {
                writeDescriptor(
                    service = operation.service,
                    characteristic = operation.characteristic,
                    descriptor = operation.descriptor,
                    bytes = operation.bytes,
                )
            }
            is ProfileOperation.ReadCharacteristic -> {
                readCharacteristic(
                    service = operation.service,
                    characteristic = operation.characteristic,
                )
            }
        }
    }
    private fun onReadCharacteristic(
        service: UUID,
        characteristic: UUID,
    ) {
        Log.d(TAG, "On read $service/$characteristic...")
        val state = state.value
        if (state !is State.Connected) TODO("on read $service/$characteristic state: $state")
        val operation = ProfileOperation.ReadCharacteristic(
            service = service,
            characteristic = characteristic,
        )
        profileOperations.add(operation)
        when (state.type) {
            State.Connected.Type.READY -> {
                performOperations()
            }
            else -> {
                // noop
            }
        }
    }

    private fun writeCharacteristic(
        service: UUID,
        characteristic: UUID,
        bytes: ByteArray,
    ) {
        val state = state.value
        if (state !is State.Connected) TODO("write $service/$characteristic state: $state")
        if (state.type != State.Connected.Type.OPERATING) TODO("write $service/$characteristic state type: ${state.type}")
        Log.d(TAG, "write $service/$characteristic...")
        launchCatching(
            block = {
                withContext(Dispatchers.Default) {
                    GattUtil.writeCharacteristic(
                        gatt = checkNotNull(gatt),
                        service = service,
                        characteristic = characteristic,
                        bytes = bytes,
                    )
                }
            },
            onSuccess = {
                // todo
            },
            onFailure = {
                when (it) {
                    is GattException -> {
                        when (it.type) {
                            GattException.Type.CHARACTERISTIC_WRITING_WAS_NOT_INITIATED -> {
                                Log.w(TAG, "Writing $service/$characteristic was not initiated!")
                            }
                            else -> {
                                // noop
                            }
                        }
                    }
                    else -> {
                        TODO("write $service/$characteristic error: $it")
                    }
                }
            },
        )
    }

    private fun writeDescriptor(
        service: UUID,
        characteristic: UUID,
        descriptor: UUID,
        bytes: ByteArray,
    ) {
        val state = state.value
        if (state !is State.Connected) TODO("write $descriptor of $service/$characteristic state: $state")
        if (state.type != State.Connected.Type.OPERATING) TODO("write $descriptor of service/$characteristic state type: ${state.type}")
        Log.d(TAG, "write $descriptor of $service/$characteristic...")
        launchCatching(
            block = {
                withContext(Dispatchers.Default) {
                    GattUtil.writeDescriptor(
                        gatt = checkNotNull(gatt),
                        service = service,
                        characteristic = characteristic,
                        descriptor = descriptor,
                        bytes = bytes,
                    )
                }
            },
            onSuccess = {
                // todo
            },
            onFailure = {
                when (it) {
                    is GattException -> {
                        when (it.type) {
                            GattException.Type.DESCRIPTOR_WRITING_WAS_NOT_INITIATED -> {
                                Log.w(TAG, "Writing $descriptor of $service/$characteristic was not initiated!")
                            }
                            else -> {
                                // noop
                            }
                        }
                    }
                    else -> {
                        TODO("write $service/$characteristic error: $it")
                    }
                }
            },
        )
    }

    private fun readCharacteristic(
        service: UUID,
        characteristic: UUID,
    ) {
        val state = state.value
        if (state !is State.Connected) TODO("read $service/$characteristic state: $state")
        if (state.type != State.Connected.Type.OPERATING) TODO("read $service/$characteristic state type: ${state.type}")
        Log.d(TAG, "read $service/$characteristic...")
        launchCatching(
            block = {
                withContext(Dispatchers.Default) {
                    GattUtil.readCharacteristic(
                        gatt = checkNotNull(gatt),
                        service = service,
                        characteristic = characteristic,
                    )
                }
            },
            onSuccess = {
                // todo
            },
            onFailure = {
                when (it) {
                    is GattException -> {
                        when (it.type) {
                            GattException.Type.READING_WAS_NOT_INITIATED -> {
                                Log.w(TAG, "Reading $service/$characteristic was not initiated!")
                            }
                            else -> {
                                // noop
                            }
                        }
                    }
                    else -> {
                        TODO("read $service/$characteristic error: $it")
                    }
                }
            },
        )
    }

    private fun onWriteCharacteristic(
        service: UUID,
        characteristic: UUID,
        bytes: ByteArray,
    ) {
        Log.d(TAG, "On write characteristic ${bytes.map { String.format("%03d", it.toInt() and 0xFF) }}...")
        val state = state.value
        if (state !is State.Connected) TODO("on write $service/$characteristic state: $state")
        val operation = ProfileOperation.WriteCharacteristic(service = service, characteristic = characteristic, bytes)
        profileOperations.add(operation)
        when (state.type) {
            State.Connected.Type.READY -> {
                performOperations()
            }
            else -> {
                // noop
            }
        }
    }

    private fun onWriteDescriptor(
        service: UUID,
        characteristic: UUID,
        descriptor: UUID,
        bytes: ByteArray,
    ) {
        Log.d(TAG, "on write descriptor ${bytes.map { String.format("%03d", it.toInt() and 0xFF) }}...")
        val state = state.value
        if (state !is State.Connected) TODO("on write $descriptor of $service/$characteristic state: $state")
        val operation = ProfileOperation.WriteDescriptor(
            service = service,
            characteristic = characteristic,
            descriptor = descriptor,
            bytes = bytes,
        )
        profileOperations.add(operation)
        when (state.type) {
            State.Connected.Type.READY -> {
                performOperations()
            }
            else -> {
                // noop
            }
        }
    }

    private fun onPair(pin: String?) {
        Log.d(TAG, "on pair...")
        val state = state.value
        if (state !is State.Connected) TODO("pair state: $state")
        if (state.type != State.Connected.Type.READY) TODO("pair state.type: ${state.type}")
        if (state.isPaired) TODO("Already paired!")
        _state.value = state.copy(type = State.Connected.Type.PAIRING)
        scope.launch {
            runCatching {
                withContext(Dispatchers.Default) {
                    val device = requireBTAdapter()
                        .getRemoteDevice(state.address)
                        ?: TODO("No device ${state.address}!")
                    when (val bondState = device.bondState) {
                        BluetoothDevice.BOND_NONE -> {
                            this@BLEGattService.pin = pin
                            if (!device.createBond()) TODO("Create bond error!")
                        }
                        BluetoothDevice.BOND_BONDING -> {
                            if (pin != null) {
                                if (!device.setPin(pin.toByteArray())) TODO("Create bond error!")
                            }
                        }
                        BluetoothDevice.BOND_BONDED -> TODO("Already bonded!")
                        else -> TODO("Bond state $bondState unsupported!")
                    }
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

    private fun onUnpair() {
        Log.d(TAG, "on unpair...")
        val state = state.value
        if (state !is State.Connected) TODO("unpair state: $state")
        if (state.type != State.Connected.Type.READY) TODO("unpair state.type: ${state.type}")
        if (!state.isPaired) TODO("Already unpaired!")
        _state.value = state.copy(type = State.Connected.Type.UNPAIRING)
        scope.launch {
            runCatching {
                withContext(Dispatchers.Default) {
                    val device = requireBTAdapter().getRemoteDevice(state.address)
                        ?: TODO("No device ${state.address}!")
                    if (!device.removeBond()) TODO("Remove bond error!")
                }
            }.fold(
                onSuccess = {
                    // todo
                },
                onFailure = {
                    TODO("GATT unpair error: $it!")
                },
            )
        }
    }

    private fun onRequestServices() {
        Log.d(TAG, "on request services...")
        val state = state.value
        if (state !is State.Connected) TODO("On request services state: $state")
        if (state.type != State.Connected.Type.READY) TODO("On request services state type: ${state.type}")
        _state.value = state.copy(type = State.Connected.Type.DISCOVER)
        scope.launch {
            runCatching {
                withContext(Dispatchers.Default) {
                    val gatt = gatt ?: TODO("No GATT!")
                    if (!gatt.discoverServices()) TODO("Discover services error!")
                }
            }.fold(
                onSuccess = {
                    // todo
                },
                onFailure = {
                    TODO("On request services error: $it!")
                },
            )
        }
    }

    private fun onSetCharacteristicNotification(
        service: UUID,
        characteristic: UUID,
        value: Boolean,
    ) {
        Log.d(TAG, "on set characteristic notification...")
        val state = state.value
        if (state !is State.Connected) TODO("On set characteristic notification state: $state")
        if (state.type != State.Connected.Type.READY) TODO("On set characteristic notification state type: ${state.type}")
        launchCatching(
            block = {
                withContext(Dispatchers.Default) {
                    GattUtil.setCharacteristicNotification(
                        gatt = gatt ?: TODO("No GATT!"),
                        service = service,
                        characteristic = characteristic,
                        value = value,
                    )
                }
            },
            onSuccess = {
                scope.launch {
                    _profileBroadcast.emit(
                        Profile.Broadcast.OnSetCharacteristicNotification(
                            service = service,
                            characteristic = characteristic,
                            value = value,
                        ),
                    )
                }
            },
            onFailure = {
                TODO("On set characteristic notification error: $it!")
            },
        )
    }

    private fun onStartCommand(intent: Intent) {
        val intentAction = intent.action ?: TODO("No intent action!")
        if (intentAction.isEmpty()) TODO("Intent action is empty!")
        when (Action.values().firstOrNull { it.name == intentAction }) {
            Action.CONNECT -> {
                val address = intent.getStringExtra("address")
                if (address.isNullOrEmpty()) TODO()
                val scanSettings = intent.getParcelableExtra<ScanSettings>("scanSettings") ?: TODO()
                onConnect(
                    address = address,
                    scanSettings = scanSettings,
                )
            }
            Action.SEARCH_STOP -> onStopSearch()
            Action.DISCONNECT -> onDisconnect()
            Action.REQUEST_SERVICES -> onRequestServices()
            Action.SET_CHARACTERISTIC_NOTIFICATION -> {
                val extras = intent.extras ?: TODO("No extras!")
                if (!extras.containsKey("value")) TODO("No value!")
                val service = intent.getStringExtra("service")
                    ?.let(UUID::fromString)
                    ?: TODO("No service!")
                val characteristic = intent.getStringExtra("characteristic")
                    ?.let(UUID::fromString)
                    ?: TODO("No characteristic!")
                onSetCharacteristicNotification(
                    service = service,
                    characteristic = characteristic,
                    value = intent.getBooleanExtra("value", false),
                )
            }
            Action.READ_CHARACTERISTIC -> {
                val service = intent.getStringExtra("service")
                    ?.let(UUID::fromString)
                    ?: TODO("No service!")
                val characteristic = intent.getStringExtra("characteristic")
                    ?.let(UUID::fromString)
                    ?: TODO("No characteristic!")
                onReadCharacteristic(
                    service = service,
                    characteristic = characteristic,
                )
            }
            Action.WRITE_CHARACTERISTIC -> {
                val state = state.value
                if (state !is State.Connected) {
                    Log.d(TAG, "Nothing will be written. Already disconnected.")
                    return
                }
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
            Action.WRITE_DESCRIPTOR -> {
                val state = state.value
                if (state !is State.Connected) {
                    Log.d(TAG, "Nothing will be written. Already disconnected.")
                    return
                }
                val bytes = intent.getByteArrayExtra("bytes") ?: TODO("No bytes!")
                val service = intent.getStringExtra("service")
                    ?.let(UUID::fromString)
                    ?: TODO("No service!")
                val characteristic = intent.getStringExtra("characteristic")
                    ?.let(UUID::fromString)
                    ?: TODO("No characteristic!")
                val descriptor = intent.getStringExtra("descriptor")
                    ?.let(UUID::fromString)
                    ?: TODO("No descriptor!")
                onWriteDescriptor(
                    service = service,
                    characteristic = characteristic,
                    descriptor = descriptor,
                    bytes = bytes,
                )
            }
            Action.PAIR -> {
                val pin = intent.getStringExtra("pin")
                if (pin != null) {
                    check(checkPIN(value = pin))
                }
                onPair(pin)
            }
            Action.UNPAIR -> onUnpair()
            else -> TODO("Unknown action: ${intent.action}!")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) TODO("No intent!")
        onStartCommand(intent)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun onNewState(oldState: State, newState: State) {
        if (oldState is State.Connected) {
            if (newState is State.Connected) {
                when (oldState.type) {
                    State.Connected.Type.READY -> {
                        when (newState.type) {
                            State.Connected.Type.PAIRING -> {
                                val filter = IntentFilter().also {
                                    if (pin != null) {
                                        it.priority = IntentFilter.SYSTEM_HIGH_PRIORITY
                                    }
                                    it.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
                                }
                                registerReceiver(receiversPairing, filter)
                            }
                            else -> {
                                // noop
                            }
                        }
                    }
                    State.Connected.Type.PAIRING -> {
                        when (newState.type) {
                            State.Connected.Type.PAIRING -> {
                                // noop
                            }
                            else -> {
                                unregisterReceiver(receiversPairing)
                            }
                        }
                    }
                    else -> {
                        // noop
                    }
                }
            } else {
                unregisterReceiver(receiversConnected)
            }
        } else {
            if (newState is State.Connected) {
                val filter = IntentFilter().also {
                    it.priority = IntentFilter.SYSTEM_HIGH_PRIORITY
                    it.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                    it.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                }
                registerReceiver(receiversConnected, filter)
                scope.launch {
                    _broadcast.emit(Broadcast.OnConnect(newState))
                }
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
                scope.launch {
                    _broadcast.emit(Broadcast.OnDisconnect)
                }
                scanSettings = null
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "on create[${hashCode()}]...") // todo
        state
            .onEach { newState ->
                oldState.also {
                    if (it != newState) {
                        onNewState(it, newState)
                        oldState = newState
                    }
                }
            }
            .launchIn(scope)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "on destroy...")
        job.cancel()
    }

    private enum class Action {
        CONNECT,
        DISCONNECT,
        SEARCH_STOP,
        REQUEST_SERVICES,
        SET_CHARACTERISTIC_NOTIFICATION,
        READ_CHARACTERISTIC,
        WRITE_CHARACTERISTIC,
        WRITE_DESCRIPTOR,
        PAIR,
        UNPAIR,
    }

    private sealed interface ProfileOperation {
        class WriteCharacteristic(
            val service: UUID,
            val characteristic: UUID,
            val bytes: ByteArray,
        ) : ProfileOperation
        class WriteDescriptor(
            val service: UUID,
            val characteristic: UUID,
            val descriptor: UUID,
            val bytes: ByteArray,
        ) : ProfileOperation
        class ReadCharacteristic(
            val service: UUID,
            val characteristic: UUID,
        ) : ProfileOperation
    }

    object Profile {
        sealed interface Broadcast {
            class OnWriteCharacteristic(
                val service: UUID,
                val characteristic: UUID,
                val bytes: ByteArray,
            ) : Broadcast
            class OnWriteDescriptor(
                val service: UUID,
                val characteristic: UUID,
                val descriptor: UUID,
                val bytes: ByteArray,
            ) : Broadcast
            class OnReadCharacteristic(
                val service: UUID,
                val characteristic: UUID,
                val bytes: ByteArray,
            ) : Broadcast
            class OnChangeCharacteristic(
                val service: UUID,
                val characteristic: UUID,
                val bytes: ByteArray,
            ) : Broadcast
            class OnServicesDiscovered(
                val services: List<BluetoothGattService>, // todo platform!
            ) : Broadcast
            class OnSetCharacteristicNotification(
                val service: UUID,
                val characteristic: UUID,
                val value: Boolean,
            ) : Broadcast
        }

        val broadcast = _profileBroadcast.asSharedFlow()

        fun requestServices(context: Context) {
            val intent = intent(context, Action.REQUEST_SERVICES)
            context.startService(intent)
        }

        fun setCharacteristicNotification(
            context: Context,
            service: UUID,
            characteristic: UUID,
            value: Boolean,
        ) {
            val intent = intent(context, Action.SET_CHARACTERISTIC_NOTIFICATION)
            intent.putExtra("service", service.toString())
            intent.putExtra("characteristic", characteristic.toString())
            intent.putExtra("value", value)
            context.startService(intent)
        }

        fun writeCharacteristic(
            context: Context,
            service: UUID,
            characteristic: UUID,
            bytes: ByteArray,
        ) {
            val intent = intent(context, Action.WRITE_CHARACTERISTIC)
            intent.putExtra("service", service.toString())
            intent.putExtra("characteristic", characteristic.toString())
            intent.putExtra("bytes", bytes)
            context.startService(intent)
        }

        fun readCharacteristic(
            context: Context,
            service: UUID,
            characteristic: UUID,
        ) {
            val intent = intent(context, Action.READ_CHARACTERISTIC)
            intent.putExtra("service", service.toString())
            intent.putExtra("characteristic", characteristic.toString())
            context.startService(intent)
        }

        fun writeDescriptor(
            context: Context,
            service: UUID,
            characteristic: UUID,
            descriptor: UUID,
            bytes: ByteArray,
        ) {
            val intent = intent(context, Action.WRITE_DESCRIPTOR)
            intent.putExtra("service", service.toString())
            intent.putExtra("characteristic", characteristic.toString())
            intent.putExtra("descriptor", descriptor.toString())
            intent.putExtra("bytes", bytes)
            context.startService(intent)
        }
    }

    companion object {
        private const val TAG = "[Gatt]"

        private val _broadcast = MutableSharedFlow<Broadcast>()
        @JvmStatic
        val broadcast = _broadcast.asSharedFlow()

        private var oldState: State = State.Disconnected
        private val _state = MutableStateFlow<State>(State.Disconnected)
        @JvmStatic
        val state = _state.asStateFlow()

        private val _profileBroadcast = MutableSharedFlow<Profile.Broadcast>()

        private fun intent(context: Context, action: Action): Intent {
            val intent = Intent(context, BLEGattService::class.java)
            intent.action = action.name
            return intent
        }

        @JvmStatic
        fun connect(context: Context, address: String, scanSettings: ScanSettings) {
            val intent = intent(context, Action.CONNECT)
            intent.putExtra("address", address)
            intent.putExtra("scanSettings", scanSettings as Parcelable)
            context.startService(intent)
        }

        @JvmStatic
        fun disconnect(context: Context) {
            val intent = intent(context, Action.DISCONNECT)
            context.startService(intent)
        }

        @JvmStatic
        fun searchStop(context: Context) {
            val intent = intent(context, Action.SEARCH_STOP)
            context.startService(intent)
        }

        fun pair(context: Context) {
            val intent = intent(context, Action.PAIR)
            context.startService(intent)
        }

        @JvmStatic
        fun pair(context: Context, pin: String) {
            val intent = intent(context, Action.PAIR)
            intent.putExtra("pin", pin)
            context.startService(intent)
        }

        fun unpair(context: Context) {
            val intent = intent(context, Action.UNPAIR)
            context.startService(intent)
        }
    }
}
