package test.android.ble.module.bluetooth

import android.app.Notification
import android.app.Service
import android.bluetooth.BluetoothAdapter
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
import androidx.lifecycle.flowWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import test.android.ble.entity.BTDevice

internal class BLEScannerService : Service() {
    sealed interface Broadcast {
        class OnError(val error: Throwable) : Broadcast
        class OnState(val state: State) : Broadcast
        class OnBTDevice(
            val device: BTDevice,
            val rawData: ByteArray,
        ) : Broadcast
    }

    enum class State {
        NONE,
        STARTED,
        STOPPED,
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private val receivers = object : BroadcastReceiver() {
        private fun onReceive(intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    Log.d(TAG, "Bluetooth adapter state: $state")
                    when (state) {
                        BluetoothAdapter.STATE_OFF -> {
                            onScanStop()
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
                        if (state.value == State.STARTED) {
                            onScanStop()
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
    private val scanner = BLEScanner(
        context = this,
        scope = scope,
        onScanResult = ::onScanResult,
        timeoutUntil = {
            state.value != State.STOPPED
        },
    )

    private fun onScanResult(result: ScanResult) {
        val scanRecord = result.scanRecord ?: return
        val device = result.device ?: return
        val btDevice = BTDevice(
            address = device.address ?: return,
            name = device.name ?: return,
        )
        val rawData = scanRecord.bytes ?: return
        scope.launch {
            _broadcast.emit(
                Broadcast.OnBTDevice(btDevice, rawData = rawData),
            )
        }
    }

    private fun onScanStart(scanSettings: ScanSettings) {
        scope.launch {
            _state.value = State.NONE
            runCatching {
                withContext(Dispatchers.Default) {
                    scanner.start(scanSettings)
                }
            }.fold(
                onSuccess = {
                    val filter = IntentFilter().also {
                        it.addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                        it.addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
                    }
                    registerReceiver(receivers, filter)
                    _state.value = State.STARTED
                },
                onFailure = {
                    _broadcast.emit(Broadcast.OnError(it))
                    _state.value = State.STOPPED
                },
            )
        }
    }

    private fun onScanStop() {
        scope.launch {
            _state.value = State.NONE
            runCatching {
                withContext(Dispatchers.Default) {
                    scanner.stop()
                }
            }.fold(
                onSuccess = {
                    _state.value = State.STOPPED
                },
                onFailure = {
                    _broadcast.emit(Broadcast.OnError(it))
                    _state.value = State.STOPPED
                },
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            unregisterReceiver(receivers)
        }
    }

    private fun onStartCommand(intent: Intent) {
        when (intent.action) {
            ACTION_SCAN_START -> {
                val scanSettings = intent.getParcelableExtra<ScanSettings>("scanSettings") ?: TODO()
                onScanStart(scanSettings = scanSettings)
            }
            ACTION_SCAN_STOP -> onScanStop()
            ACTION_START_FOREGROUND -> {
                if (!intent.hasExtra("notificationId")) TODO()
                val notificationId = intent.getIntExtra("notificationId", -1)
                if (!intent.hasExtra("notification")) TODO()
                val notification = intent.getParcelableExtra<Notification>("notification") ?: TODO()
                startForeground(notificationId, notification)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) onStartCommand(intent)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            withContext(Dispatchers.Default) {
                var oldState = state.value
                state.collect { newState ->
                    if (oldState != newState) {
                        _broadcast.emit(Broadcast.OnState(newState))
                    }
                    oldState = newState
                }
            }
        }
    }

    companion object {
        private const val TAG = "[BLE|SS]"
        val ACTION_SCAN_START = "${this::class.java.name}:ACTION_SCAN_START"
        val ACTION_SCAN_STOP = "${this::class.java.name}:ACTION_SCAN_STOP"
        val ACTION_START_FOREGROUND = "${this::class.java.name}:ACTION_START_FOREGROUND"

        private val _broadcast = MutableSharedFlow<Broadcast>()
        @JvmStatic
        val broadcast = _broadcast.asSharedFlow()
        private val _state = MutableStateFlow(State.STOPPED)
        @JvmStatic
        val state = _state.asStateFlow()

        @JvmStatic
        fun scanStart(context: Context, scanSettings: ScanSettings) {
            val intent = Intent(context, BLEScannerService::class.java)
            intent.action = ACTION_SCAN_START
            intent.putExtra("scanSettings", scanSettings as Parcelable)
            context.startService(intent)
        }

        @JvmStatic
        fun scanStop(context: Context) {
            val intent = Intent(context, BLEScannerService::class.java)
            intent.action = ACTION_SCAN_STOP
            context.startService(intent)
        }

        @JvmStatic
        fun startForeground(context: Context, notificationId: Int, notification: Notification) {
            val intent = Intent(context, BLEScannerService::class.java)
            intent.action = ACTION_START_FOREGROUND
            intent.putExtra("notificationId", notificationId)
            intent.putExtra("notification", notification)
            context.startService(intent)
        }
    }
}
