package test.android.ble.module.scanner

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import test.android.ble.module.bluetooth.BLEScannerService
import test.android.ble.util.android.showToast
import test.android.ble.util.compose.toPaddings

@Composable
internal fun ScannerScreen() {
    val context = LocalContext.current
    val insets = LocalView.current.rootWindowInsets.toPaddings()
    val scanState by BLEScannerService.scanState.collectAsState(BLEScannerService.ScanState.NONE)
    LaunchedEffect(Unit) {
        BLEScannerService.broadcast.collect { broadcast ->
            when (broadcast) {
                is BLEScannerService.Broadcast.OnError -> {
                    when (broadcast.error) {
                        BLEScannerService.Error.BT_NO_ADAPTER -> context.showToast("No adapter!")
                        BLEScannerService.Error.BT_NO_PERMISSION -> context.showToast("No permission!")
                        BLEScannerService.Error.BT_ADAPTER_DISABLED -> context.showToast("Adapter disabled!")
                        BLEScannerService.Error.BT_NO_SCANNER -> context.showToast("No scanner!")
                        BLEScannerService.Error.BT_NO_SCAN_PERMISSION -> context.showToast("No scan permission!")
                        BLEScannerService.Error.BT_LOCATION_DISABLED -> context.showToast("Location disabled!")
                        null -> context.showToast("Unknown error!")
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
            // todo
        }
        val text = when (scanState) {
            BLEScannerService.ScanState.NONE -> "..."
            BLEScannerService.ScanState.STARTED -> "stop"
            BLEScannerService.ScanState.STOPPED -> "start"
        }
        BasicText(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clickable(enabled = scanState != BLEScannerService.ScanState.NONE) {
                    val intent = Intent(context, BLEScannerService::class.java)
                    when (scanState) {
                        BLEScannerService.ScanState.NONE -> TODO()
                        BLEScannerService.ScanState.STARTED -> {
                            intent.action = BLEScannerService.ACTION_SCAN_STOP
                        }
                        BLEScannerService.ScanState.STOPPED -> {
                            intent.action = BLEScannerService.ACTION_SCAN_START
                        }
                    }
                    context.startService(intent)
                }
                .wrapContentSize(),
            text = text,
            style = TextStyle(
                textAlign = TextAlign.Center,
                color = Color.Black,
                fontSize = 16.sp,
            ),
        )
    }
}
