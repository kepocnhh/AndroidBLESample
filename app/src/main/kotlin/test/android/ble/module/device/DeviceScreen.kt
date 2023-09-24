package test.android.ble.module.device

import android.util.Log
import androidx.compose.foundation.background
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import sp.ax.jc.clicks.clicks
import sp.ax.jc.clicks.onClick
import test.android.ble.App
import test.android.ble.module.bluetooth.BLEGattService
import test.android.ble.util.android.BLEException
import test.android.ble.util.android.BTException
import test.android.ble.util.android.LocException
import test.android.ble.util.android.PairException
import test.android.ble.util.android.checkPIN
import test.android.ble.util.android.showToast
import test.android.ble.util.compose.AutoCompleteTextField
import test.android.ble.util.compose.toPaddings
import java.math.BigInteger
import java.util.UUID

@Composable
private fun Button(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
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
            .clicks(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            )
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
private fun <T : Any> DialogListSelect(
    dialogVisible: () -> Boolean,
    onDismissRequest: () -> Unit,
    title: String,
    itemsSupplier: () -> List<T>,
    onSelect: (T) -> Unit,
) {
    if (!dialogVisible()) return
    Dialog(onDismissRequest = onDismissRequest) {
        ListSelect(
            title = title,
            items = itemsSupplier(),
            onClick = {
                onDismissRequest()
                onSelect(it)
            },
        )
    }
}

@Composable
private fun DialogEnterBytes(
    dialogVisible: () -> Boolean,
    onDismissRequest: () -> Unit,
    titlesSupplier: () -> List<String>,
    writes: Set<String>,
    onBytes: (ByteArray) -> Unit,
) {
    if (!dialogVisible()) return
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
        )
    ) {
        Column(
            modifier = Modifier
                .background(Color.White),
        ) {
            titlesSupplier().forEach { title ->
                BasicText(
                    modifier = Modifier
                        .height(48.dp)
                        .fillMaxWidth()
                        .wrapContentHeight(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    text = title,
                )
            }
            Spacer(modifier = Modifier
                .height(1.dp)
                .fillMaxWidth()
                .background(Color.Black))
            val stringBytesState = remember { mutableStateOf(TextFieldValue()) }
            val parsedBytes = runCatching {
                BigInteger(stringBytesState.value.text, 16).toByteArray()
            }.getOrNull()
            AutoCompleteTextField(
                value = stringBytesState.value,
                onValueChange = {
                    stringBytesState.value = it
                },
                values = writes,
                showTips = parsedBytes != null,
            )
            Spacer(modifier = Modifier
                .height(1.dp)
                .fillMaxWidth()
                .background(Color.Black))
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
                        onDismissRequest()
                        onBytes(checkNotNull(parsedBytes))
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

@Composable
private fun Characteristics(
    writeState: MutableState<Boolean>,
    gattState: BLEGattService.State,
    writes: Set<String>,
) {
    if (gattState !is BLEGattService.State.Connected) return
    if (gattState.type != BLEGattService.State.Connected.Type.READY) return
    val context = LocalContext.current
    val selectedServiceState = remember { mutableStateOf<UUID?>(null) }
    val selectedCharacteristicState = remember { mutableStateOf<Pair<UUID, UUID>?>(null) }
    val selectedService = selectedServiceState.value
    val selectedCharacteristic = selectedCharacteristicState.value
    DialogListSelect(
        dialogVisible = writeState::value,
        onDismissRequest = {
            writeState.value = false
        },
        title = "Service",
        itemsSupplier = {
            gattState.services.keys.sorted()
        },
        onSelect = {
            selectedServiceState.value = it
        }
    )
    DialogListSelect(
        dialogVisible = {
            selectedServiceState.value != null
        },
        onDismissRequest = {
            selectedServiceState.value = null
        },
        title = "Characteristic",
        itemsSupplier = {
            gattState.services[selectedService]!!.keys.sorted()
        },
        onSelect = {
            selectedCharacteristicState.value = selectedService!! to it
        }
    )
    DialogEnterBytes(
        dialogVisible = { selectedCharacteristicState.value != null },
        onDismissRequest = { selectedCharacteristicState.value = null },
        titlesSupplier = {
            val (service, characteristic) = selectedCharacteristic!!
            listOf(
                "Service: $service",
                "Characteristic: $characteristic",
            )
        },
        writes = writes,
        onBytes = {
            val (service, characteristic) = selectedCharacteristic!!
            BLEGattService.writeCharacteristic(
                context = context,
                service = service,
                characteristic = characteristic,
                bytes = it,
            )
        },
    )
}

@Composable
private fun Descriptors(
    writeState: MutableState<Boolean>,
    gattState: BLEGattService.State,
    writes: Set<String>,
) {
    val context = LocalContext.current
    val selectedServiceState = remember { mutableStateOf<UUID?>(null) }
    val selectedCharacteristicState = remember { mutableStateOf<Pair<UUID, UUID>?>(null) }
    val selectedDescriptorState = remember { mutableStateOf<Triple<UUID, UUID, UUID>?>(null) }
    if (writeState.value) {

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
    val viewModel = App.viewModel<DeviceViewModel>()
    val writes by viewModel.writes.collectAsState()
    if (writes == null) viewModel.requestWrites()
//    val gattState = BLEGattService.state.collectAsState().value
    val gattState: BLEGattService.State = BLEGattService.State.Connected(
        address = address,
        type = BLEGattService.State.Connected.Type.READY,
        isPaired = true,
        services = mapOf(
            UUID.fromString("00000000-cc7a-482a-984a-7f2ed5b3e58f") to mapOf(
                UUID.fromString("00000000-8e22-4541-9d4c-21edae82ed19") to setOf(
                    UUID.fromString("00000000-8e22-4541-9d4c-21edae82ed20"),
                ),
            ),
        ),
    )
    val writeCharacteristicsState = remember { mutableStateOf(false) }
    Characteristics(
        writeState = writeCharacteristicsState,
        gattState = gattState,
        writes = writes.orEmpty(),
    )
    val clearWritesDialogState = remember { mutableStateOf(false) }
    if (clearWritesDialogState.value) {
        Dialog(
            onDismissRequest = {
                clearWritesDialogState.value = false
            },
        ) {
            check(gattState is BLEGattService.State.Connected)
            check(gattState.type == BLEGattService.State.Connected.Type.READY)
            Column(
                modifier = Modifier
                    .background(Color.White)
                    .padding(8.dp),
            ) {
                BasicText(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxWidth(),
                    text = "Do you want to clear saved writes?",
                )
                BasicText(
                    modifier = Modifier
                        .align(Alignment.End)
                        .onClick {
                            clearWritesDialogState.value = false
                            viewModel.clearWrites()
                        }
                        .padding(8.dp),
                    text = "Yes",
                )
            }
        }
    }
    val pairPinDialogState = remember { mutableStateOf(false) }
    if (pairPinDialogState.value) {
        Dialog(
            onDismissRequest = {
                pairPinDialogState.value = false
            },
        ) {
            check(gattState is BLEGattService.State.Connected)
            check(gattState.type == BLEGattService.State.Connected.Type.READY)
            check(!gattState.isPaired)
            Column(
                modifier = Modifier
                    .background(Color.White)
                    .padding(8.dp),
            ) {
                val pinState = remember { mutableStateOf(TextFieldValue()) }
                Box(
                    modifier = Modifier
                        .height(64.dp)
                        .fillMaxWidth(),
                ) {
                    val textStyle = TextStyle(
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center,
                        color = Color.Black,
                        letterSpacing = 4.sp,
                    )
                    BasicTextField(
                        modifier = Modifier
                            .fillMaxSize()
                            .wrapContentHeight(),
                        singleLine = true,
                        maxLines = 1,
                        value = pinState.value,
                        onValueChange = {
                            if (it.text.length > 6) {
                                pinState.value = it.copy(text = it.text.substring(0, 6))
                            } else {
                                pinState.value = it
                            }
                        },
                        textStyle = textStyle,
                    )
                    if (pinState.value.text.isEmpty()) {
                        BasicText(
                            modifier = Modifier
                                .fillMaxSize()
                                .wrapContentHeight(),
                            text = "000000",
                            style = textStyle.copy(color = Color.Gray),
                        )
                    }
                }
                val enabled = checkPIN(pinState.value.text)
                BasicText(
                    modifier = Modifier
                        .align(Alignment.End)
                        .onClick(enabled = enabled) {
                            pairPinDialogState.value = false
                            BLEGattService.pair(context, pin = pinState.value.text)
                        }
                        .padding(8.dp),
                    text = "Set PIN",
                    style = TextStyle(
                        color = if (enabled) Color.Black else Color.Red,
                    )
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
                is BLEGattService.Broadcast.OnWrite -> {
                    val bytes = broadcast.notification.bytes
                    val value = String.format("%0${bytes.size * 2}x", BigInteger(1, bytes))
                    viewModel.write(value)
                }
                is BLEGattService.Broadcast.OnChanged -> {
                    // todo
                }
                is BLEGattService.Broadcast.OnPair -> {
                    broadcast.result.fold(
                        onSuccess = {
                            // todo
                        },
                        onFailure = {
                            when (it) {
                                is PairException -> {
                                    when (it.error) {
                                        PairException.Error.FAILED -> context.showToast("Pair failed!")
                                        PairException.Error.REJECTED -> context.showToast("Pair rejected!")
                                        PairException.Error.CANCELED -> context.showToast("Pair canceled!")
                                        else -> context.showToast("Unknown pair error!")
                                    }
                                }
                                else -> context.showToast("Unknown error!")
                            }
                        },
                    )
                }
                BLEGattService.Broadcast.OnDisconnect -> {
                    context.showToast("Disconnected.")
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
            when (gattState) {
                is BLEGattService.State.Connected -> {
                    val isReady = gattState.type == BLEGattService.State.Connected.Type.READY
                    Button(
                        text = "write characteristic",
                        enabled = isReady && gattState.isPaired,
                        onClick = {
                            writeCharacteristicsState.value = true
                        },
                        onLongClick = {
                            clearWritesDialogState.value = true
                        },
                    )
                    val pairText = when (gattState.type) {
                        BLEGattService.State.Connected.Type.PAIRING -> "pairing..."
                        BLEGattService.State.Connected.Type.UNPAIRING -> "unpairing..."
                        else -> if (gattState.isPaired) "unpair" else "pair"
                    }
                    Button(
                        text = pairText,
                        enabled = isReady,
                        onClick = {
                            if (gattState.isPaired) {
                                BLEGattService.unpair(context)
                            } else {
                                BLEGattService.pair(context)
                            }
                        },
                        onLongClick = {
                            if (!gattState.isPaired) {
                                pairPinDialogState.value = true
                            }
                        }
                    )
                    Button(
                        text = "disconnect",
                        enabled = true,
                        onClick = {
                            BLEGattService.disconnect(context)
                        },
                    )
                }
                BLEGattService.State.Disconnected -> {
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
                is BLEGattService.State.Search -> {
                    Button(
                        text = "stop search",
                        enabled = gattState.canStop(),
                        onClick = {
                            BLEGattService.searchStop(context)
                        },
                    )
                }
                is BLEGattService.State.Connecting -> {
                    BasicText(
                        modifier = Modifier
                            .height(48.dp)
                            .fillMaxWidth()
                            .wrapContentSize(),
                        text = "connecting...",
                        style = TextStyle(color = Color.Gray),
                    )
                }
                is BLEGattService.State.Disconnecting -> {
                    BasicText(
                        modifier = Modifier
                            .height(48.dp)
                            .fillMaxWidth()
                            .wrapContentSize(),
                        text = "disconnecting...",
                        style = TextStyle(color = Color.Gray),
                    )
                }
            }
        }
    }
}
