package test.android.ble.module.bluetooth

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class BLEScannerException(val error: BLEScannerService.Error) : Exception()

internal class BLEScannerService : Service() {
    sealed interface Broadcast {
        class OnError(val error: Error?) : Broadcast
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
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            println("$TAG: on scan result: callback $callbackType result $result")
            // todo
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

    private fun getBluetoothAdapter(): BluetoothAdapter {
        val manager = getSystemService(BluetoothManager::class.java)
        val adapter: BluetoothAdapter? = manager.adapter
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
        println("$TAG: scanner $scanner")
        try {
            scanner.startScan(callback)
        } catch (e: SecurityException) {
            throw BLEScannerException(Error.BT_NO_SCAN_PERMISSION)
        }
    }

    @Deprecated(message = "replace with scanStart")
    private fun getScanner(): BluetoothLeScanner {
        val manager = getSystemService(BluetoothManager::class.java)
        val adapter: BluetoothAdapter? = manager.adapter
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
        println("$TAG: adapter is enabled")
        val scanner: BluetoothLeScanner? = adapter.bluetoothLeScanner
        if (scanner == null) {
            throw BLEScannerException(Error.BT_NO_SCANNER)
        }
        println("$TAG: scanner $scanner")
        return scanner
    }

    private fun onScanStart() {
        scope.launch {
            runCatching {
                val adapter = getBluetoothAdapter()
                withContext(Dispatchers.Default) {
                    adapter.scanStart()
                }
            }.fold(
                onSuccess = {
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
        _scanState.value = ScanState.NONE
        runCatching(::getScanner).fold(
            onSuccess = {
                it.stopScan(callback)
            },
            onFailure = {
                scope.launch {
                    val error = when (it) {
                        is BLEScannerException -> it.error
                        else -> null
                    }
                    _broadcast.emit(Broadcast.OnError(error))
                    _scanState.value = ScanState.STOPPED
                }
            },
        )
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

        private val _broadcast = MutableSharedFlow<Broadcast>()
        val broadcast = _broadcast.asSharedFlow()
        private val _scanState = MutableStateFlow(ScanState.STOPPED)
        val scanState = _scanState.asStateFlow()
    }
}
