package test.android.ble.module.device

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import test.android.ble.util.compose.toPaddings

@Composable
internal fun DeviceScreen(
    address: String,
    onForget: () -> Unit,
) {
    val insets = LocalView.current.rootWindowInsets.toPaddings()
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
        BasicText(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(64.dp)
                .clickable(onClick = onForget)
                .wrapContentHeight(),
            text = "forget",
            style = TextStyle(
                textAlign = TextAlign.Center,
                color = Color.Black,
                fontSize = 16.sp,
            ),
        )
    }
}
