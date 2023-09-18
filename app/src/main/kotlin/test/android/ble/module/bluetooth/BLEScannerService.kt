package test.android.ble.module.bluetooth

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import test.android.ble.R
import test.android.ble.entity.BluetoothDevice
import kotlin.math.absoluteValue

internal class BLEScannerException(val error: BLEScannerService.Error) : Exception()

internal class BLEScannerService : Service() {
    sealed interface Broadcast {
        class OnError(val error: Error?) : Broadcast
        class OnBTDevice(val device: BluetoothDevice) : Broadcast
    }

    enum class ScanState {
        NONE,
        STARTED,
        STOPPED,
    }

    enum class Error {
        BT_NO_ADAPTER,
        BT_NO_PERMISSION,
        BT_ADAPTER_DISABLED,
        BT_NO_SCANNER,
        BT_NO_SCAN_PERMISSION,
        BT_LOCATION_DISABLED,
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            println("$TAG: on scan result: callback $callbackType result $result")
            if (result == null) return
            val scanRecord = result.scanRecord ?: return
            val device = result.device ?: return
            val btDevice = BluetoothDevice(
                address = device.address ?: return,
                name = device.name ?: return,
            )
            scope.launch {
                _broadcast.emit(
                    Broadcast.OnBTDevice(btDevice),
                )
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            println("$TAG: on batch scan results: $results")
            // todo
        }

        override fun onScanFailed(errorCode: Int) {
            println("$TAG: error: $errorCode")
            // todo
        }
    }
    private val receivers = object : BroadcastReceiver() {
        private fun onReceive(intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                        BluetoothAdapter.STATE_OFF -> {
                            onScanStop()
                        }
                        BluetoothAdapter.STATE_ON -> {
                            // todo
                        }
                        else -> {
                            // noop
                        }
                    }
                }
                LocationManager.PROVIDERS_CHANGED_ACTION -> {
                    val locationManager = getSystemService(LocationManager::class.java)
                    val isLocationEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    if (!isLocationEnabled) {
                        onScanStop()
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

    private fun getBluetoothAdapter(): BluetoothAdapter {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val adapter: BluetoothAdapter? = bluetoothManager.adapter
        if (adapter == null) {
            throw BLEScannerException(Error.BT_NO_ADAPTER)
        }
        val isEnabled = try {
            adapter.isEnabled
        } catch (e: SecurityException) {
            throw BLEScannerException(Error.BT_NO_PERMISSION)
        }
        if (!isEnabled) {
            throw BLEScannerException(Error.BT_ADAPTER_DISABLED)
        }
        return adapter
    }

    private fun BluetoothAdapter.scanStart() {
        val scanner: BluetoothLeScanner? = bluetoothLeScanner
        if (scanner == null) {
            throw BLEScannerException(Error.BT_NO_SCANNER)
        }
        val locationManager = getSystemService(LocationManager::class.java)
        val isLocationEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        if (!isLocationEnabled) {
            throw BLEScannerException(Error.BT_LOCATION_DISABLED)
        }
        try {
            scanner.startScan(callback)
        } catch (e: SecurityException) {
            throw BLEScannerException(Error.BT_NO_SCAN_PERMISSION)
        }
    }

    private fun BluetoothAdapter.scanStop() {
        val scanner: BluetoothLeScanner? = bluetoothLeScanner
        if (scanner == null) {
            throw BLEScannerException(Error.BT_NO_SCANNER)
        }
        scanner.stopScan(callback)
    }

    private fun startForeground() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val intent = Intent(this, BLEScannerService::class.java)
        intent.action = ACTION_SCAN_STOP
        val pendingIntent = PendingIntent.getService(this, -1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("scanning...")
            .setSmallIcon(R.drawable.bt)
            .addAction(-1, "stop", pendingIntent)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun onScanStart() {
        scope.launch {
            _scanState.value = ScanState.NONE
            runCatching {
                val adapter = getBluetoothAdapter()
                withContext(Dispatchers.Default) {
                    adapter.scanStart()
                }
            }.fold(
                onSuccess = {
                    startForeground()
                    val filter = IntentFilter().also {
                        it.addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                        it.addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
                    }
                    registerReceiver(receivers, filter)
                    _scanState.value = ScanState.STARTED
                },
                onFailure = {
                    val error = when (it) {
                        is BLEScannerException -> it.error
                        else -> {
                            println("$TAG: $it")
                            null
                        }
                    }
                    _broadcast.emit(Broadcast.OnError(error))
                    _scanState.value = ScanState.STOPPED
                },
            )
        }
    }

    private fun onScanStop() {
        scope.launch {
            _scanState.value = ScanState.NONE
            runCatching {
                val adapter = getBluetoothAdapter()
                withContext(Dispatchers.Default) {
                    adapter.scanStop()
                }
            }.fold(
                onSuccess = {
                    _scanState.value = ScanState.STOPPED
                },
                onFailure = {
                    val error = when (it) {
                        is BLEScannerException -> it.error
                        else -> {
                            println("$TAG: $it")
                            null
                        }
                    }
                    _broadcast.emit(Broadcast.OnError(error))
                    _scanState.value = ScanState.STOPPED
                },
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            unregisterReceiver(receivers)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel: NotificationChannel? = notificationManager.getNotificationChannel(CHANNEL_ID)
            if (channel == null) {
                notificationManager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
        }
    }

    private fun onStartCommand(intent: Intent) {
        when (intent.action) {
            ACTION_SCAN_START -> onScanStart()
            ACTION_SCAN_STOP -> onScanStop()
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
        private const val TAG = "[BLEScanner]"
        val ACTION_SCAN_START = "${this::class.java.name}:ACTION_SCAN_START"
        val ACTION_SCAN_STOP = "${this::class.java.name}:ACTION_SCAN_STOP"

        private val CHANNEL_ID = "${this::class.java.name}:CHANNEL:BLEScanner"
        private const val CHANNEL_NAME = "BLE Scanner"
        private val NOTIFICATION_ID = System.currentTimeMillis().toInt().absoluteValue

        private val _broadcast = MutableSharedFlow<Broadcast>()
        val broadcast = _broadcast.asSharedFlow()
        private val _scanState = MutableStateFlow(ScanState.STOPPED)
        val scanState = _scanState.asStateFlow()

        fun start(context: Context, action: String) {
            val intent = Intent(context, BLEScannerService::class.java)
            intent.action = action
            context.startService(intent)
        }
    }
}
