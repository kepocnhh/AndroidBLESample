package test.android.ble.util.android

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context

internal class BTException(val error: Error) : Exception() {
    enum class Error {
        NO_ADAPTER,
        NO_PERMISSION,
        DISABLED,
    }
}

internal fun Context.requireBTAdapter(): BluetoothAdapter {
    val bluetoothManager = getSystemService(BluetoothManager::class.java)
    val adapter: BluetoothAdapter = bluetoothManager.adapter
        ?: throw BTException(BTException.Error.NO_ADAPTER)
    val isEnabled = try {
        adapter.isEnabled
    } catch (e: SecurityException) {
        throw BTException(BTException.Error.NO_PERMISSION)
    }
    if (!isEnabled) throw BTException(BTException.Error.DISABLED)
    return adapter
}
