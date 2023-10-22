package test.android.ble.module.bluetooth

import android.app.Notification
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
import test.android.ble.util.android.BTException
import test.android.ble.util.android.GattException
import test.android.ble.util.android.GattUtil
import test.android.ble.util.android.LocException
import test.android.ble.util.android.PairException
import test.android.ble.util.android.ServiceUtil
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

/**
 * [Bluetooth](https://android.googlesource.com/platform/packages/modules/Bluetooth/)
 * [Sources](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android13-dev/framework/java/android/bluetooth)
 */
internal class BLEGattService : Service() {
    sealed interface Broadcast {
        class OnError(val error: Throwable) : Broadcast
        class OnPair(val result: Result<BTDevice>) : Broadcast
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
        object Disconnected : State {
            override fun toString(): String {
                return "Disconnected"
            }
        }
    }

    sealed interface Event {
        object OnDisconnected : Event
        class OnConnecting(val address: String) : Event
        class OnConnected(val address: String) : Event
        class OnDisconnecting(val address: String) : Event
        object OnSearchWaiting : Event
        class OnSearchComing(val address: String) : Event
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private var oldState: State = State.Disconnected
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

    /**
     * [BluetoothGattCallback](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android13-dev/framework/java/android/bluetooth/BluetoothGattCallback.java)
     */
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

        @Deprecated(message = "todo")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    if (characteristic == null) TODO("On characteristic read success. But no characteristic!")
                    Log.d(TAG, "On characteristic [DEPRECATED] ${characteristic.service.uuid}/${characteristic.uuid} read success.")
                    onCharacteristicRead(characteristic)
                }
                else -> {
                    if (characteristic == null) {
                        Log.w(TAG, "On characteristic [DEPRECATED] read status: $status!")
                    } else {
                        Log.w(TAG, "On characteristic [DEPRECATED] ${characteristic.service.uuid}/${characteristic.uuid} read status: $status!")
                    }
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
            Log.i(TAG, "On characteristic [DEPRECATED] ${characteristic.service.uuid}/${characteristic.uuid} changed.")
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

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    if (gatt == null) TODO("No gatt!")
                    Log.i(TAG, "On MTU changed to $mtu success.")
                    onMtuChanged(size = mtu)
                }
                else -> {
                    Log.w(TAG, "On MTU changed status: $status!")
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
                    if (device.address != state.address) {
                        Log.d(TAG, "Expected device ${state.address}. So I ignore device ${device.address}.")
                        return
                    }
                    when (oldState) {
                        BluetoothDevice.BOND_NONE -> {
                            when (newState) {
                                BluetoothDevice.BOND_BONDING -> {
                                    if (state.type == State.Connected.Type.PAIRING) {
                                        Log.d(TAG, "Pairing request to ${device.address}...")
                                        return
                                    }
                                    if (!state.isPaired) {
                                        Log.d(TAG, "Pairing request to ${device.address} externally.")
                                        return
                                    }
                                }
                                BluetoothDevice.BOND_BONDED -> {
                                    // todo check device is bonded
                                    if (state.type == State.Connected.Type.PAIRING) {
                                        onBonded(device) // todo
                                        return
                                    }
                                    if (!state.isPaired) {
                                        Log.i(TAG, "The external pairing request to ${device.address} has been accepted.")
                                        _state.value = state.copy(isPaired = true)
                                        onPair(device = device)
                                        return
                                    }
                                }
                            }
                        }
                        BluetoothDevice.BOND_BONDING -> {
                            when (newState) {
                                BluetoothDevice.BOND_NONE -> {
                                    // todo check device is unpaired
                                    when {
                                        state.type == State.Connected.Type.PAIRING -> {
                                            pin = null
                                            val reasonKey = "android.bluetooth.device.extra.REASON"
                                            if (!intent.hasExtra(reasonKey)) {
                                                Log.w(TAG, "No pairing with unknown reason!")
                                                onBondingFailed(address = device.address, null)
                                                return
                                            }
                                            val reason = intent.getIntExtra(reasonKey, BluetoothDevice.ERROR)
                                            val error = getPairErrorOrNull(reason = reason)
                                            if (error == null) {
                                                Log.w(TAG, "No pairing because unknown error! Code: $reason")
                                            } else {
                                                logPairError(error)
                                            }
                                            onBondingFailed(address = device.address, error)
                                        }
                                        state.type == State.Connected.Type.UNPAIRING -> {
                                            onUnpair(device) // todo
                                        }
                                        state.isPaired -> {
                                            Log.d(TAG, "The device ${device.address} was unpaired externally.")
                                            _state.value = state.copy(isPaired = false)
                                        }
                                        else -> {
                                            Log.d(TAG, "The device ${device.address} was not bonded.")
                                        }
                                    }
                                    return
                                }
                                BluetoothDevice.BOND_BONDED -> {
                                    // todo check device is bonded
                                    if (state.type == State.Connected.Type.PAIRING) {
                                        onBonded(device) // todo
                                        return
                                    }
                                    if (!state.isPaired) {
                                        Log.i(TAG, "The external pairing request to ${device.address} has been accepted.")
                                        _state.value = state.copy(isPaired = true)
                                        onPair(device = device)
                                        return
                                    }
                                }
                            }
                        }
                        BluetoothDevice.BOND_BONDED -> {
                            when (newState) {
                                BluetoothDevice.BOND_NONE -> {
                                    // todo check device is unpaired
                                    if (state.type == State.Connected.Type.UNPAIRING) {
                                        onUnpair(device) // todo
                                        return
                                    }
                                    if (state.isPaired) {
                                        Log.d(TAG, "The device ${device.address} was unpaired externally.")
                                        _state.value = state.copy(isPaired = false)
                                        return
                                    }
                                }
                                BluetoothDevice.BOND_BONDING -> {
                                    if (state.type == State.Connected.Type.UNPAIRING) {
                                        Log.d(TAG, "The device ${device.address} is unpairing...")
                                        return
                                    }
                                    if (state.isPaired) {
                                        Log.d(TAG, "The device ${device.address} is unpairing externally.")
                                        return
                                    }
                                }
                            }
                        }
                    }
                    val message = """
                        * State: $state.
                        * Bond state changed: $oldState -> $newState.
                        The service is not ready for pairing/unpairing device ${state.address}!
                    """.trimIndent()
                    error(message)
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

    private fun getPairErrorOrNull(reason: Int): PairException.Error? {
        val UNBOND_REASON_AUTH_FAILED = 1
        val UNBOND_REASON_AUTH_REJECTED = 2
        val UNBOND_REASON_AUTH_CANCELED = 3
        val UNBOND_REASON_REMOVED = 9
        return when (reason) {
            UNBOND_REASON_AUTH_FAILED -> PairException.Error.FAILED
            UNBOND_REASON_AUTH_REJECTED -> PairException.Error.REJECTED
            UNBOND_REASON_AUTH_CANCELED -> PairException.Error.CANCELED
            UNBOND_REASON_REMOVED -> PairException.Error.REMOVED
            else -> null
        }
    }

    private fun logPairError(error: PairException.Error) {
        when (error) {
            PairException.Error.FAILED -> {
                Log.w(TAG, "No pairing because auth failed!")
            }
            PairException.Error.REJECTED -> {
                Log.w(TAG, "No pairing because auth rejected!")
            }
            PairException.Error.CANCELED -> {
                Log.w(TAG, "No pairing because auth canceled!")
            }
            PairException.Error.REMOVED -> {
                Log.w(TAG, "No pairing because removed!")
            }
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

    @Deprecated(message = "only used locally")
    private fun onUnpair(device: BluetoothDevice) {
        val state = state.value
        if (state !is State.Connected) TODO("State: $state!")
        if (state.type != State.Connected.Type.UNPAIRING) TODO("State type: ${state.type}!")
        if (!state.isPaired) TODO("Already unpaired!")
        if (device.address != state.address) TODO("Expected ${state.address}, actual ${device.address}!")
        Log.i(TAG, "The device ${device.address} is no longer paired.")
        _state.value = state.copy(type = State.Connected.Type.READY, isPaired = false)
    }

    private fun onPair(device: BluetoothDevice) {
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

    @Deprecated(message = "only used locally")
    private fun onBonded(device: BluetoothDevice) {
        val state = state.value
        if (state !is State.Connected) TODO("State: $state!")
        if (state.type != State.Connected.Type.PAIRING) TODO("State type: ${state.type}!")
        if (state.isPaired) TODO("Already paired!")
        if (device.address != state.address) TODO("Expected ${state.address}, actual ${device.address}!")
        Log.i(TAG, "The device ${device.address} is bonded.")
        _state.value = state.copy(type = State.Connected.Type.READY, isPaired = true)
        onPair(device = device)
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

    @Deprecated(message = "to operations")
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

    private fun onMtuChanged(size: Int) {
        val state = state.value
        if (state !is State.Connected) {
            Log.d(TAG, "The changed results are no longer relevant. Disconnected.")
            return
        }
        if (state.type != State.Connected.Type.OPERATING) TODO("On MTU changed state type: ${state.type}")
        performOperations()
        scope.launch {
            _profileBroadcast.emit(
                Profile.Broadcast.OnMtuChanged(size = size),
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
        if (state !is State.Connecting) TODO("On services discovered state: $state")
        if (state.type != State.Connecting.Type.DISCOVER) TODO()
        _state.value = State.Connected(
            address = state.address,
            isPaired = gatt.device.bondState == BluetoothDevice.BOND_BONDED,
            type = State.Connected.Type.READY,
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
        fromWaiting()
    }

    private suspend fun toComing() {
        val state = state.value
        if (state !is State.Search) TODO("To coming state: $state")
        if (state.type != State.Search.Type.WAITING) TODO("To coming state type: ${state.type}")
        val scanSettings = scanSettings ?: TODO("State search. Type waiting. But no scan settings!")
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
        val state = state.value
        if (state !is State.Search) TODO("From waiting state: $state")
        if (state.type != State.Search.Type.WAITING) TODO("From waiting state type: ${state.type}")
        scope.launch {
            val isBTEnabled = runCatching(::isBTEnabled).getOrDefault(false)
            if (!isBTEnabled) {
                Log.w(TAG, "BT disabled!")
            } else if (!isLocationEnabled()) {
                Log.w(TAG, "Location disabled!")
            } else {
                toComing()
            }
        }
    }

    private fun toWaiting() {
        Log.d(TAG, "to waiting...")
        val state = state.value
        if (state !is State.Search) TODO("To waiting state: $state")
        if (state.type != State.Search.Type.COMING) TODO("To waiting state type: ${state.type}")
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
//                    startForegroundWaiting()
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
                else -> TODO("State: $state. Type: ${state.type}. But no operation!")
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
            is ProfileOperation.SetCharacteristicNotification -> {
                setCharacteristicNotification(
                    service = operation.service,
                    characteristic = operation.characteristic,
                    value = operation.value,
                )
            }
            is ProfileOperation.RequestMtu -> {
                requestMtu(size = operation.size)
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
                if (it is GattException && it.type == GattException.Type.READING_WAS_NOT_INITIATED) {
                    Log.w(TAG, "Reading $service/$characteristic was not initiated!")
                    _broadcast.emit(Broadcast.OnError(it))
                    performOperations()
                } else {
                    TODO("read $service/$characteristic error: $it")
                }
            },
        )
    }

    private fun onNotificationStatusSet(
        service: UUID,
        characteristic: UUID,
        value: Boolean,
    ) {
        val state = state.value
        if (state !is State.Connected) {
            Log.d(TAG, "The setting results are no longer relevant. Disconnected.")
            return
        }
        if (state.type != State.Connected.Type.OPERATING) TODO("on $service/$characteristic notification set state type: ${state.type}")
        performOperations()
        scope.launch {
            _profileBroadcast.emit(
                Profile.Broadcast.OnSetCharacteristicNotification(
                    service = service,
                    characteristic = characteristic,
                    value = value,
                ),
            )
        }
    }

    private fun setCharacteristicNotification(
        service: UUID,
        characteristic: UUID,
        value: Boolean,
    ) {
        val state = state.value
        if (state !is State.Connected) TODO("set $service/$characteristic notification state: $state")
        if (state.type != State.Connected.Type.OPERATING) TODO("set $service/$characteristic notification state type: ${state.type}")
        Log.d(TAG, "set $service/$characteristic notification...")
        launchCatching(
            block = {
                withContext(Dispatchers.Default) {
                    GattUtil.setCharacteristicNotification(
                        gatt = gatt ?: TODO("Set $service/$characteristic notification. No GATT!"),
                        service = service,
                        characteristic = characteristic,
                        value = value,
                    )
                }
            },
            onSuccess = {
                onNotificationStatusSet(
                    service = service,
                    characteristic = characteristic,
                    value = value,
                )
            },
            onFailure = {
                if (it is GattException && it.type == GattException.Type.NOTIFICATION_STATUS_WAS_NOT_SUCCESSFULLY_SET) {
                    Log.w(TAG, "The $service/$characteristic notification status was not successfully set!")
                    _broadcast.emit(Broadcast.OnError(it))
                    performOperations()
                } else {
                    TODO("set $service/$characteristic notification error: $it")
                }
            },
        )
    }

    private fun requestMtu(size: Int) {
        val state = state.value
        if (state !is State.Connected) TODO("request MTU state: $state")
        if (state.type != State.Connected.Type.OPERATING) TODO("request MTU state type: ${state.type}")
        Log.d(TAG, "request MTU $size...")
        launchCatching(
            block = {
                withContext(Dispatchers.Default) {
                    GattUtil.requestMtu(
                        gatt = checkNotNull(gatt),
                        size = size,
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
                            GattException.Type.MTU_VALUE_WAS_NOT_REQUESTED -> {
                                Log.w(TAG, "The new MTU value was not successfully requested!")
                            }
                            else -> {
                                // noop
                            }
                        }
                    }
                    else -> {
                        TODO("request MTU error: $it")
                    }
                }
            },
        )
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

    private fun onWriteDescriptor(
        service: UUID,
        characteristic: UUID,
        descriptor: UUID,
        bytes: ByteArray,
    ) {
        Log.d(TAG, "on write $descriptor of $service/$characteristic...")
        val state = state.value
        if (state !is State.Connected) TODO("on write $descriptor of $service/$characteristic state: $state")
        val operation = ProfileOperation.WriteDescriptor(
            service = service,
            characteristic = characteristic,
            descriptor = descriptor,
            bytes = bytes,
        )
        profileOperations.add(operation)
        if (state.type != State.Connected.Type.READY) return
        performOperations()
    }

    private fun onWriteCharacteristic(
        service: UUID,
        characteristic: UUID,
        bytes: ByteArray,
    ) {
        Log.d(TAG, "on write $service/$characteristic...")
        val state = state.value
        if (state !is State.Connected) TODO("on write $service/$characteristic state: $state")
        val operation = ProfileOperation.WriteCharacteristic(
            service = service,
            characteristic = characteristic,
            bytes = bytes,
        )
        profileOperations.add(operation)
        if (state.type != State.Connected.Type.READY) return
        performOperations()
    }

    private fun onReadCharacteristic(
        service: UUID,
        characteristic: UUID,
    ) {
        Log.d(TAG, "on read $service/$characteristic...")
        val state = state.value
        if (state !is State.Connected) TODO("on read $service/$characteristic state: $state")
        val operation = ProfileOperation.ReadCharacteristic(
            service = service,
            characteristic = characteristic,
        )
        profileOperations.add(operation)
        if (state.type != State.Connected.Type.READY) return
        performOperations()
    }

    private fun onSetCharacteristicNotification(
        service: UUID,
        characteristic: UUID,
        value: Boolean,
    ) {
        Log.d(TAG, "on set $service/$characteristic notification...")
        val state = state.value
        if (state !is State.Connected) TODO("on set $service/$characteristic notification state: $state")
        val operation = ProfileOperation.SetCharacteristicNotification(
            service = service,
            characteristic = characteristic,
            value = value,
        )
        profileOperations.add(operation)
        if (state.type != State.Connected.Type.READY) return
        performOperations()
    }

    private fun onRequestMTU(size: Int) {
        Log.d(TAG, "on request MTU...")
        val state = state.value
        if (state !is State.Connected) TODO("on request MTU state: $state")
        val operation = ProfileOperation.RequestMtu(size = size)
        profileOperations.add(operation)
        if (state.type != State.Connected.Type.READY) return
        performOperations()
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
                val state = state.value
                if (state !is State.Connected) {
                    Log.d(TAG, "Nothing will be set. Already disconnected.")
                    return
                }
                if (!intent.hasExtra("value")) TODO("No value!")
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
                val state = state.value
                if (state !is State.Connected) {
                    Log.d(TAG, "Nothing will be read. Already disconnected.")
                    return
                }
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
            Action.REQUEST_MTU -> {
                val state = state.value
                if (state !is State.Connected) {
                    Log.d(TAG, "Nothing will be request. Already disconnected.")
                    return
                }
                if (!intent.hasExtra("size")) TODO("No size!")
                val size = intent.getIntExtra("size", -1)
                onRequestMTU(size = size)
            }
            else -> {
                when (intentAction) {
                    ServiceUtil.ACTION_START_FOREGROUND -> {
                        val notificationId: Int = ServiceUtil.getNotificationId(intent)
                        val notification: Notification = ServiceUtil.getNotification(intent)
                        startForeground(notificationId, notification)
                    }
                    ServiceUtil.ACTION_STOP_FOREGROUND -> {
                        val notificationBehavior: Int = ServiceUtil.getNotificationBehavior(intent)
                        stopForeground(notificationBehavior)
                    }
                    else -> TODO("Unknown action: $intentAction!")
                }
            }
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

    private suspend fun onState(newState: State) {
        val oldState = oldState
        this.oldState = newState
        when (oldState) {
            is State.Connected -> {
                if (newState !is State.Connected) {
                    val message = """
                          * $oldState
                         /
                        * $newState
                        unregister receiver connected
                    """.trimIndent()
                    Log.d(TAG, message)
                    unregisterReceiver(receiversConnected)
                }
            }
            State.Disconnected -> {
                if (newState is State.Disconnected) return
                val filter = IntentFilter().also {
                    it.addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                    it.addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
                }
                val message = """
                    * $oldState
                     \
                      * $newState
                    register receivers
                """.trimIndent()
                Log.d(TAG, message)
                registerReceiver(receivers, filter)
            }
            else -> {
                // noop
            }
        }
        when (newState) {
            is State.Connected -> {
                if (oldState is State.Connected) {
                    when (newState.type) {
                        State.Connected.Type.PAIRING -> {
                            if (oldState.type != State.Connected.Type.READY) return
                            val filter = IntentFilter().also {
                                if (pin != null) {
                                    it.priority = IntentFilter.SYSTEM_HIGH_PRIORITY
                                }
                                it.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
                            }
                            registerReceiver(receiversPairing, filter)
                        }
                        else -> {
                            if (oldState.type != State.Connected.Type.PAIRING) return
                            unregisterReceiver(receiversPairing)
                        }
                    }
                } else {
                    _event.emit(Event.OnConnected(address = newState.address))
                    val filter = IntentFilter().also {
                        it.priority = IntentFilter.SYSTEM_HIGH_PRIORITY
                        it.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                        it.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                    }
                    val message = """
                        * $oldState
                         \
                          * $newState
                        register receiver connected
                    """.trimIndent()
                    Log.d(TAG, message)
                    registerReceiver(receiversConnected, filter)
                }
            }
            is State.Connecting -> {
                if (oldState is State.Connecting) return
                _event.emit(Event.OnConnecting(address = newState.address))
            }
            State.Disconnected -> {
                if (oldState is State.Disconnected) return
                _event.emit(Event.OnDisconnected)
                val message = """
                      * $oldState
                     /
                    * $newState
                    unregister receivers
                """.trimIndent()
                Log.d(TAG, message)
                unregisterReceiver(receivers)
                scanSettings = null
            }
            is State.Disconnecting -> {
                if (oldState is State.Disconnecting) return
                _event.emit(Event.OnDisconnecting(address = newState.address))
            }
            is State.Search -> {
                when (newState.type) {
                    State.Search.Type.WAITING -> {
                        if (oldState is State.Search && oldState.type == State.Search.Type.WAITING) return
                        _event.emit(Event.OnSearchWaiting)
                    }
                    State.Search.Type.COMING -> {
                        if (oldState is State.Search && oldState.type == State.Search.Type.COMING) return
                        _event.emit(Event.OnSearchComing(address = newState.address))
                    }
                    else -> {
                        // noop
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "on create[${hashCode()}]...") // todo
        state
            .onEach(::onState)
//            .onEach { newState ->
//                oldState.also {
//                    if (it != newState) {
//                        onNewState(it, newState)
//                        oldState = newState
//                    }
//                }
//            }
            .launchIn(scope)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "on destroy...")
        job.cancel()
    }

    enum class Action {
        CONNECT,
        DISCONNECT,
        SEARCH_STOP,
        REQUEST_SERVICES,
        SET_CHARACTERISTIC_NOTIFICATION,
        READ_CHARACTERISTIC,
        WRITE_CHARACTERISTIC,
        WRITE_DESCRIPTOR,
        REQUEST_MTU,
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
        class SetCharacteristicNotification(
            val service: UUID,
            val characteristic: UUID,
            val value: Boolean,
        ) : ProfileOperation
        data class RequestMtu(val size: Int) : ProfileOperation
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
            data class OnMtuChanged(val size: Int) : Broadcast
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

        fun requestMtu(
            context: Context,
            size: Int,
        ) {
            val intent = intent(context, Action.REQUEST_MTU)
            intent.putExtra("size", size)
            context.startService(intent)
        }
    }

    companion object {
        private const val TAG = "[Gatt]"

        private val _broadcast = MutableSharedFlow<Broadcast>()
        @JvmStatic
        val broadcast = _broadcast.asSharedFlow()

        private val _event = MutableSharedFlow<Event>()
        @JvmStatic
        val event = _event.asSharedFlow()

        private val _state = MutableStateFlow<State>(State.Disconnected)
        @JvmStatic
        val state = _state.asStateFlow()

        private val _profileBroadcast = MutableSharedFlow<Profile.Broadcast>()

        fun intent(context: Context, action: Action): Intent {
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
