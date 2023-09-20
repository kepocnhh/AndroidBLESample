package test.android.ble.module.device

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import test.android.ble.module.bluetooth.BLEGattService
import test.android.ble.util.android.BLEException
import test.android.ble.util.android.BTException
import test.android.ble.util.android.LocException
import test.android.ble.util.android.showToast
import test.android.ble.util.compose.toPaddings

@Composable
private fun Button(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val textStyle = TextStyle(
        textAlign = TextAlign.Center,
        color = Color.Black,
        fontSize = 16.sp,
    )
    val disabledTextStyle = textStyle.copy(color = Color.Gray)
    BasicText(
        modifier = Modifier
            .height(48.dp)
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .wrapContentSize(),
        text = text,
        style = if (enabled) textStyle else disabledTextStyle,
    )
}

@Composable
internal fun DeviceScreen(
    address: String,
    onForget: () -> Unit,
) {
    val TAG = "[Device|Screen]"
    val context = LocalContext.current
    val insets = LocalView.current.rootWindowInsets.toPaddings()
    val gattState by BLEGattService.state.collectAsState()
    LaunchedEffect(Unit) {
        BLEGattService.broadcast.collect { broadcast ->
            when (broadcast) {
                is BLEGattService.Broadcast.OnError -> {
                    when (broadcast.error) {
                        is BTException -> {
                            when (broadcast.error.error) {
                                BTException.Error.NO_ADAPTER -> context.showToast("No adapter!")
                                BTException.Error.NO_PERMISSION -> context.showToast("No permission!")
                                BTException.Error.DISABLED -> context.showToast("Adapter disabled!")
                            }
                        }
                        is LocException -> {
                            when (broadcast.error.error) {
                                LocException.Error.DISABLED -> context.showToast("Location disabled!")
                            }
                        }
                        is BLEException -> {
                            when (broadcast.error.error) {
                                BLEException.Error.NO_SCANNER -> context.showToast("No scanner!")
                                BLEException.Error.NO_SCAN_PERMISSION -> context.showToast("No scan permission!")
                            }
                        }
                        else -> {
                            Log.w(TAG, "GATT unknown error: ${broadcast.error}")
                            context.showToast("Unknown error!")
                        }
                    }
                }
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(insets),
    ) {
        BasicText(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .wrapContentHeight(),
            text = address,
            style = TextStyle(
                textAlign = TextAlign.Center,
                color = Color.Black,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
            ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            Button(
                text = "disconnect",
                enabled = gattState is BLEGattService.State.Connected,
                onClick = {
                    BLEGattService.disconnect(context)
                }
            )
            val isSearch = gattState.let {
                it is BLEGattService.State.Search && it.canStop()
            }
            Button(
                text = "stop search",
                enabled = isSearch,
                onClick = {
                    BLEGattService.searchStop(context)
                }
            )
            Button(
                text = "connect",
                enabled = gattState == BLEGattService.State.Disconnected,
                onClick = {
                    BLEGattService.connect(context, address = address)
                }
            )
            Button(
                text = "forget",
                enabled = gattState == BLEGattService.State.Disconnected,
                onClick = onForget
            )
        }
    }
}
