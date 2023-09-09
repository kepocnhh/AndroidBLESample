package test.android.ble.module.scanner

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import test.android.ble.BluetoothService
import test.android.ble.util.compose.toPaddings

@Composable
internal fun ScannerScreen() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        BluetoothService.broadcast.collect { broadcast ->
            when (broadcast) {
                else -> TODO()
            }
        }
    }
    val insets = LocalView.current.rootWindowInsets.toPaddings()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(insets),
    ) {
        val scanState = remember { mutableStateOf(false) }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            // todo
        }
        BasicText(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clickable {
                    val intent = Intent(context, BluetoothService::class.java)
                    if (scanState.value) {
                        intent.action = BluetoothService.ACTION_SCAN_STOP
                    } else {
                        intent.action = BluetoothService.ACTION_SCAN_START
                    }
                    scanState.value = !scanState.value
                    context.startService(intent)
                }
                .wrapContentSize(),
            text = if (scanState.value) "stop" else "start",
            style = TextStyle(
                textAlign = TextAlign.Center,
                color = Color.Black,
                fontSize = 16.sp,
            ),
        )
    }
}
