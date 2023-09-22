package test.android.ble.module.device

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import sp.ax.jc.clicks.onClick
import test.android.ble.module.bluetooth.BLEGattService
import test.android.ble.util.android.BLEException
import test.android.ble.util.android.BTException
import test.android.ble.util.android.LocException
import test.android.ble.util.android.showToast
import test.android.ble.util.compose.toPaddings
import java.math.BigInteger
import java.util.UUID

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
private fun <T : Any> ListSelect(
    title: String,
    items: List<T>,
    onClick: (T) -> Unit,
) {
    Column(
        modifier = Modifier
            .background(Color.White),
    ) {
        BasicText(
            modifier = Modifier
                .height(48.dp)
                .wrapContentHeight()
                .align(Alignment.CenterHorizontally),
            text = title,
        )
        Spacer(modifier = Modifier
            .height(1.dp)
            .fillMaxWidth()
            .background(Color.Black))
        LazyColumn(
            modifier = Modifier
                .heightIn(max = 128.dp),
        ) {
            items(
                count = items.size,
                key = {
                    items[it].toString()
                }
            ) { index ->
                val item = items[index]
                BasicText(
                    modifier = Modifier
                        .height(48.dp)
                        .fillMaxWidth()
                        .onClick {
                            onClick(item)
                        }
                        .wrapContentHeight()
                        .padding(start = 8.dp),
                    text = item.toString(),
                )
            }
        }
    }
}

@Composable
internal fun DeviceScreen(
    address: String,
    onForget: () -> Unit,
) {
    val TAG = "[Device|Screen]"
    val context = LocalContext.current
    val insets = LocalView.current.rootWindowInsets.toPaddings()
    val gattState = BLEGattService.state.collectAsState().value
//    val gattState: BLEGattService.State = BLEGattService.State.Connected(
//        address = address,
//        type = BLEGattService.State.Connected.Type.READY,
//        services = mapOf(
//            UUID.fromString("00000000-cc7a-482a-984a-7f2ed5b3e58f") to setOf(
//                UUID.fromString("00000000-8e22-4541-9d4c-21edae82ed19"),
//            ),
//        )
//    )
    val selectServiceDialogState = remember { mutableStateOf(false) }
    val selectedServiceState = remember { mutableStateOf<UUID?>(null) }
    val selectedCharacteristicState = remember { mutableStateOf<Pair<UUID, UUID>?>(null) }
    val selectedService = selectedServiceState.value
    val selectedCharacteristic = selectedCharacteristicState.value
    if (selectServiceDialogState.value) {
        Dialog(
            onDismissRequest = {
                selectServiceDialogState.value = false
            },
        ) {
            check(gattState is BLEGattService.State.Connected)
            check(gattState.type == BLEGattService.State.Connected.Type.READY)
            ListSelect(
                title = "Service",
                items = gattState.services.keys.sorted(),
                onClick = {
                    selectServiceDialogState.value = false
                    selectedServiceState.value = it
                },
            )
        }
    } else if (selectedService != null) {
        Dialog(
            onDismissRequest = {
                selectedServiceState.value = null
            },
        ) {
            check(gattState is BLEGattService.State.Connected)
            check(gattState.type == BLEGattService.State.Connected.Type.READY)
            ListSelect(
                title = "Characteristic",
                items = gattState.services[selectedService]!!.sorted(),
                onClick = {
                    selectedServiceState.value = null
                    selectedCharacteristicState.value = selectedService to it
                },
            )
        }
    } else if (selectedCharacteristic != null) {
        Dialog(
            onDismissRequest = {
                selectedCharacteristicState.value = null
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
            )
        ) {
            check(gattState is BLEGattService.State.Connected)
            check(gattState.type == BLEGattService.State.Connected.Type.READY)
            val (service, characteristic) = selectedCharacteristic
            Column(
                modifier = Modifier
                    .background(Color.White),
            ) {
                BasicText(
                    modifier = Modifier
                        .height(48.dp)
                        .fillMaxWidth()
                        .wrapContentHeight(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    text = "Service: $service",
                )
                BasicText(
                    modifier = Modifier
                        .height(48.dp)
                        .fillMaxWidth()
                        .wrapContentHeight(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    text = "Characteristic: $characteristic",
                )
                Spacer(modifier = Modifier.height(1.dp).fillMaxWidth().background(Color.Black))
                val stringBytesState = remember { mutableStateOf("") }
                BasicTextField(
                    modifier = Modifier
                        .height(48.dp)
                        .fillMaxWidth()
                        .wrapContentHeight(),
                    value = stringBytesState.value,
                    maxLines = 1,
                    singleLine = true,
                    onValueChange = {
                        stringBytesState.value = it
                    },
                )
                Spacer(modifier = Modifier.height(1.dp).fillMaxWidth().background(Color.Black))
                val parsedBytes = try {
                    BigInteger(stringBytesState.value,16).toByteArray()
                } catch (e: Throwable) {
                    null
                }
                BasicText(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentSize(),
                    text = if (parsedBytes == null) "" else {
                        parsedBytes
                            .withIndex()
                            .groupBy(keySelector = { it.index / 4 }, valueTransform = { it.value })
                            .values
                            .joinToString(separator = "\n") { list ->
                            list.joinToString(separator = " ") {
                                String.format("%03d", it.toInt() and 0xFF)
                            }
                        }
                    }, // todo
                    style = TextStyle(
                        fontSize = 14.sp,
                    ),
                )
                BasicText(
                    modifier = Modifier
                        .height(48.dp)
                        .fillMaxWidth()
                        .onClick(enabled = parsedBytes != null) {
                            selectedCharacteristicState.value = null
                            BLEGattService.writeCharacteristic(
                                context = context,
                                service = service,
                                characteristic = characteristic,
                                bytes = checkNotNull(parsedBytes),
                            )
                        }
                        .wrapContentSize(),
                    text = if (parsedBytes == null) "error" else { "write ${parsedBytes.size} bytes" },
                    style = TextStyle(
                        fontSize = 14.sp,
                        color = if (parsedBytes == null) Color.Red else Color.Black,
                    ),
                )
            }
        }
    }
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
                text = "write characteristic",
                enabled = gattState is BLEGattService.State.Connected && gattState.type == BLEGattService.State.Connected.Type.READY,
                onClick = {
                    selectServiceDialogState.value = true
                },
            )
            Button(
                text = "disconnect",
                enabled = gattState is BLEGattService.State.Connected,
                onClick = {
                    BLEGattService.disconnect(context)
                },
            )
            val isSearch = gattState.let {
                it is BLEGattService.State.Search && it.canStop()
            }
            Button(
                text = "stop search",
                enabled = isSearch,
                onClick = {
                    BLEGattService.searchStop(context)
                },
            )
            Button(
                text = "connect",
                enabled = gattState == BLEGattService.State.Disconnected,
                onClick = {
                    BLEGattService.connect(context, address = address)
                },
            )
            Button(
                text = "forget",
                enabled = gattState == BLEGattService.State.Disconnected,
                onClick = onForget
            )
        }
    }
}
