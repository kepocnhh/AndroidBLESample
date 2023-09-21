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

internal fun Context.onBTEnabled(onEnabled: (Boolean) -> Unit, onError: (Throwable) -> Unit) {
    runCatching {
        val adapter = getSystemService(BluetoothManager::class.java)
            .adapter ?: throw BTException(BTException.Error.NO_ADAPTER)
        try {
            adapter.isEnabled
        } catch (e: SecurityException) {
            throw BTException(BTException.Error.NO_PERMISSION)
        }
    }.fold(
        onSuccess = onEnabled,
        onFailure = onError,
    )
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
