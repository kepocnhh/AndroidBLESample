package test.android.ble.module.device

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import test.android.ble.util.android.showToast
import test.android.ble.util.compose.toPaddings

@Composable
internal fun DeviceScreen(
    address: String,
    onForget: () -> Unit,
) {
    val context = LocalContext.current
    val insets = LocalView.current.rootWindowInsets.toPaddings()
    val connectState by BLEGattService.connectState.collectAsState()
    LaunchedEffect(Unit) {
        BLEGattService.broadcast.collect { broadcast ->
            when (broadcast) {
                is BLEGattService.Broadcast.OnError -> {
                    when (broadcast.error) {
                        BLEGattService.Error.BT_NO_ADAPTER -> context.showToast("No adapter!")
                        BLEGattService.Error.BT_NO_PERMISSION -> context.showToast("No permission!")
                        BLEGattService.Error.BT_ADAPTER_DISABLED -> context.showToast("Adapter disabled!")
                        BLEGattService.Error.BT_NO_CONNECT_PERMISSION -> context.showToast("No connect permission!")
                        null -> context.showToast("Unknown error!")
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
        val textStyle = TextStyle(
            textAlign = TextAlign.Center,
            color = Color.Black,
            fontSize = 16.sp,
        )
        when (connectState) {
            BLEGattService.ConnectState.NONE -> {
                BasicText(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(64.dp)
                        .wrapContentHeight(),
                    text = "...",
                    style = textStyle,
                )
            }
            BLEGattService.ConnectState.CONNECTED -> {
                BasicText(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(64.dp)
                        .clickable {
                            TODO()
                        }
                        .wrapContentHeight(),
                    text = "disconnect",
                    style = textStyle,
                )
            }
            BLEGattService.ConnectState.DISCONNECTED -> {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(64.dp),
                ) {
                    BasicText(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable {
                                BLEGattService.connect(context, address = address)
                            }
                            .wrapContentSize(),
                        text = "connect",
                        style = textStyle,
                    )
                    BasicText(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(onClick = onForget)
                            .wrapContentSize(),
                        text = "forget",
                        style = textStyle,
                    )
                }
            }
        }
    }
}
