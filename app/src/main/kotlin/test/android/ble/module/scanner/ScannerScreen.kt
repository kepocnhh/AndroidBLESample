package test.android.ble.module.scanner

import android.bluetooth.le.ScanSettings
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import test.android.ble.entity.BTDevice
import test.android.ble.module.bluetooth.BLEScannerService
import test.android.ble.util.android.BLEException
import test.android.ble.util.android.BTException
import test.android.ble.util.android.LocException
import test.android.ble.util.android.showToast
import test.android.ble.util.compose.toPaddings

@Composable
internal fun ScannerScreen(onSelect: (BTDevice) -> Unit) {
    val TAG = "[Scanner|Screen]"
    val context = LocalContext.current
    val insets = LocalView.current.rootWindowInsets.toPaddings()
    val scanState by BLEScannerService.state.collectAsState()
    val devicesState = remember { mutableStateOf(listOf<BTDevice>()) }
    val scanSettings = remember {
        ScanSettings
            .Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
            .setReportDelay(0L)
            .build()
    }
    LaunchedEffect(Unit) {
        BLEScannerService.broadcast.collect { broadcast ->
            when (broadcast) {
                is BLEScannerService.Broadcast.OnError -> {
                    when (broadcast.error) {
                        is BTException -> {
                            when (broadcast.error.error) {
                                BTException.Error.NO_ADAPTER -> context.showToast("No adapter!")
                                BTException.Error.NO_PERMISSION -> context.showToast("No BT permission!")
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
                            Log.w(TAG, "BLE scanner unknown error: ${broadcast.error}")
                            context.showToast("Unknown error!")
                        }
                    }
                }
                is BLEScannerService.Broadcast.OnBTDevice -> {
                    val device = broadcast.device
                    if (devicesState.value.none { it.address == device.address }) {
                        Log.i(TAG, "device: $device")
                        devicesState.value = devicesState.value + device
                    }
                }
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(insets),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val devices = devicesState.value
            items(devices.size) { index ->
                val device = devices[index]
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .clickable {
                            when (scanState) {
                                BLEScannerService.State.STARTED -> {
                                    BLEScannerService.scanStop(context)
                                }
                                else -> {
                                    // noop
                                }
                            }
                            onSelect(device)
                        }
                        .padding(start = 16.dp, end = 16.dp),
                ) {
                    BasicText(
                        modifier = Modifier
                            .align(Alignment.CenterStart),
                        text = device.name,
                        style = TextStyle(
                            textAlign = TextAlign.Center,
                            color = Color.Black,
                            fontSize = 14.sp,
                        ),
                    )
                    BasicText(
                        modifier = Modifier
                            .align(Alignment.CenterEnd),
                        text = device.address,
                        style = TextStyle(
                            textAlign = TextAlign.Center,
                            color = Color.Black,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
            }
        }
        val text = when (scanState) {
            BLEScannerService.State.NONE -> "..."
            BLEScannerService.State.STARTED -> "stop"
            BLEScannerService.State.STOPPED -> "start"
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) {
            BasicText(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .clickable(enabled = scanState != BLEScannerService.State.NONE) {
                        when (scanState) {
                            BLEScannerService.State.STARTED -> {
                                BLEScannerService.scanStop(context)
                            }
                            BLEScannerService.State.STOPPED -> {
                                BLEScannerService.scanStart(context, scanSettings = scanSettings)
                            }
                            else -> {
                                // noop
                            }
                        }
                    }
                    .wrapContentSize(),
                text = text,
                style = TextStyle(
                    textAlign = TextAlign.Center,
                    color = Color.Black,
                    fontSize = 16.sp,
                ),
            )
            if (devicesState.value.isNotEmpty()) {
                BasicText(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .clickable {
                            devicesState.value = emptyList()
                        }
                        .wrapContentSize(),
                    text = "clear",
                    style = TextStyle(
                        textAlign = TextAlign.Center,
                        color = Color.Black,
                        fontSize = 16.sp,
                    ),
                )
            }
        }
    }
}
