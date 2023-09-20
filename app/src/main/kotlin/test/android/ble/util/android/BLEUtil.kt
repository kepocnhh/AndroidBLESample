package test.android.ble.util.android

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.content.Context

internal class BLEException(val error: Error) : Exception() {
    enum class Error {
        NO_SCANNER,
        NO_SCAN_PERMISSION,
    }
}

internal fun Context.scanStart(callback: ScanCallback) {
    val adapter = requireBTAdapter()
    checkProvider()
    val scanner: BluetoothLeScanner = adapter.bluetoothLeScanner
        ?: throw BLEException(BLEException.Error.NO_SCANNER)
    try {
        scanner.startScan(callback)
    } catch (e: SecurityException) {
        throw BLEException(BLEException.Error.NO_SCAN_PERMISSION)
    }
}

internal fun Context.scanStop(callback: ScanCallback) {
    val scanner: BluetoothLeScanner = requireBTAdapter().bluetoothLeScanner
        ?: throw BLEException(BLEException.Error.NO_SCANNER)
    try {
        scanner.stopScan(callback)
    } catch (e: SecurityException) {
        throw BLEException(BLEException.Error.NO_SCAN_PERMISSION)
    }
}
