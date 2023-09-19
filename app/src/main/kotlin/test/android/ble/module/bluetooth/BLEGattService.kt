package test.android.ble.module.bluetooth

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
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

internal class BLEGattException(val error: BLEGattService.Error) : Exception()

internal class BLEGattService : Service() {
    sealed interface Broadcast {
        class OnError(val error: Error?) : Broadcast
    }

    enum class ConnectState {
        NONE,
        CONNECTED,
        DISCONNECTED,
    }

    enum class Error {
        BT_NO_ADAPTER,
        BT_NO_PERMISSION,
        BT_ADAPTER_DISABLED,
        BT_NO_CONNECT_PERMISSION,
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private val connecteds = mutableMapOf<String, BluetoothGatt>()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (gatt == null) return // todo
            Log.d(TAG, "on connection state change $status $newState")
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            connecteds[gatt.device.address] = gatt
                            _connectState.value = ConnectState.CONNECTED
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            connecteds.remove(gatt.device.address)
                            _connectState.value = ConnectState.DISCONNECTED
                        }
                        else -> {
                            TODO("Gatt newState: $newState")
                        }
                    }
                }
                else -> {
                    TODO("Gatt status: $status")
                }
            }
        }
    }

    private fun onConnect(address: String) {
        Log.d(TAG, "connect $address...")
        if (connectState.value != ConnectState.DISCONNECTED) TODO("connect state: ${connectState.value}")
        val context: Context = this
        scope.launch {
            _connectState.value = ConnectState.NONE
            runCatching {
                val adapter = getBluetoothAdapter(context)
                withContext(Dispatchers.Default) {
                    val device = adapter.getRemoteDevice(address)
                    Log.d(TAG, "device: $device")
                    val autoConnect = false
                    try {
                        device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
                    } catch (e: SecurityException) {
                        throw BLEGattException(Error.BT_NO_CONNECT_PERMISSION)
                    }
                }
            }.fold(
                onSuccess = {
                    // todo bt on/off
                },
                onFailure = {
                    val error = (it as? BLEGattException)?.error
                    _broadcast.emit(Broadcast.OnError(error))
                    _connectState.value = ConnectState.DISCONNECTED
                }
            )
        }
    }

    private fun onDisconnect() {
        Log.d(TAG, "disconnect...")
        if (connectState.value != ConnectState.CONNECTED) TODO("connect state: ${connectState.value}")
        val context: Context = this
        scope.launch {
            _connectState.value = ConnectState.NONE
            runCatching {
                val adapter = getBluetoothAdapter(context)
                val gatt = connecteds.values.single()
                withContext(Dispatchers.Default) {
                    gatt.disconnect()
                }
            }.fold(
                onSuccess = {
                    // todo bt on/off
                },
                onFailure = {
                    val error = (it as? BLEGattException)?.error
                    _broadcast.emit(Broadcast.OnError(error))
                    _connectState.value = ConnectState.DISCONNECTED
                }
            )
        }
    }

    private fun onStartCommand(intent: Intent) {
        when (intent.action) {
            ACTION_CONNECT -> onConnect(address = intent.getStringExtra("address")!!)
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

        private val _broadcast = MutableSharedFlow<Broadcast>()
        val broadcast = _broadcast.asSharedFlow()
        private val _connectState = MutableStateFlow(ConnectState.DISCONNECTED)
        val connectState = _connectState.asStateFlow()

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

        private fun getBluetoothAdapter(context: Context): BluetoothAdapter {
            val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
            val adapter: BluetoothAdapter = bluetoothManager.adapter
                ?: throw BLEGattException(Error.BT_NO_ADAPTER)
            val isEnabled = try {
                adapter.isEnabled
            } catch (e: SecurityException) {
                throw BLEGattException(Error.BT_NO_PERMISSION)
            }
            if (!isEnabled) throw BLEGattException(Error.BT_ADAPTER_DISABLED)
            return adapter
        }
    }
}
