package test.android.ble.util.android

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context

internal class BTException(val error: Error) : Exception() {
    enum class Error {
        NO_ADAPTER,
        NO_PERMISSION,
        DISABLED,
    }
}

internal fun Context.isBTEnabled(): Boolean {
    val adapter = getSystemService(BluetoothManager::class.java)
        .adapter ?: throw BTException(BTException.Error.NO_ADAPTER)
    return try {
        adapter.isEnabled
    } catch (e: SecurityException) {
        throw BTException(BTException.Error.NO_PERMISSION)
    }
}

internal fun Context.requireBTAdapter(): BluetoothAdapter {
    val adapter = getSystemService(BluetoothManager::class.java)
        .adapter ?: throw BTException(BTException.Error.NO_ADAPTER)
    val isEnabled = try {
        adapter.isEnabled
    } catch (e: SecurityException) {
        throw BTException(BTException.Error.NO_PERMISSION)
    }
    if (!isEnabled) throw BTException(BTException.Error.DISABLED)
    return adapter
}

internal fun BluetoothDevice.removeBond(): Boolean {
    val result = javaClass.getMethod("removeBond").invoke(this)
    check(result is Boolean)
    return result
}
