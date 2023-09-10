package test.android.ble.module.router

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import test.android.ble.entity.BluetoothDevice
import test.android.ble.module.scanner.ScannerScreen

@Composable
internal fun RouterScreen() {
    val deviceState = remember { mutableStateOf<BluetoothDevice?>(null) }
    val device = deviceState.value
    if (device == null) {
        ScannerScreen()
    } else {
        TODO()
    }
}
