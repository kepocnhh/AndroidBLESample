package test.android.ble.util.android

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context

internal class BLEException(val error: Error) : Exception() {
    enum class Error {
        NO_SCANNER,
        NO_SCAN_PERMISSION,
    }
}

internal fun Context.scanStart(callback: ScanCallback, scanSettings: ScanSettings) {
    val adapter = requireBTAdapter()
    checkProvider()
    val scanner: BluetoothLeScanner = adapter.bluetoothLeScanner
        ?: throw BLEException(BLEException.Error.NO_SCANNER)
    try {
        scanner.startScan(null, scanSettings, callback)
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
