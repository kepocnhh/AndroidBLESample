package test.android.ble.module.router

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import test.android.ble.App
import test.android.ble.module.device.DeviceScreen
import test.android.ble.module.scanner.ScannerScreen
import test.android.ble.util.Optional

@Composable
internal fun RouterScreen() {
    val viewModel = App.viewModel<RouterViewModel>()
    val address = viewModel.address.collectAsState().value
    when (address) {
        Optional.None -> {
            ScannerScreen(
                onSelect = { device ->
                    viewModel.setAddress(device.address)
                },
            )
        }
        is Optional.Some -> {
            DeviceScreen(
                address = address.value,
                onForget = {
                    viewModel.forgetAddress()
                },
            )
        }
        null -> viewModel.requestAddress()
    }
}
