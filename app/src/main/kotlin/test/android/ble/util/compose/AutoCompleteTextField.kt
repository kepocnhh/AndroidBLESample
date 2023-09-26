package test.android.ble.util.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sp.ax.jc.clicks.onClick

@Composable
internal fun AutoCompleteTextField(
    modifier: Modifier = Modifier,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    values: Set<String>,
    showTips: Boolean,
) {
    Column(modifier = modifier) {
        BasicTextField(
            modifier = Modifier
                .height(48.dp)
                .fillMaxWidth()
                .wrapContentHeight(),
            value = value,
            textStyle = TextStyle(
                fontSize = 14.sp,
                color = Color.Black,
            ),
            maxLines = 1,
            singleLine = true,
            onValueChange = onValueChange,
        )
        val items = values
            .filter { it.trim().isNotEmpty() }
            .filter { it.startsWith(value.text) && it != value.text }
            .sorted()
        if (showTips && items.isNotEmpty()) {
            Spacer(
                modifier = Modifier
                    .height(1.dp)
                    .fillMaxWidth()
                    .background(Color.Gray),
            )
            LazyColumn(
                modifier = Modifier
                    .heightIn(max = 128.dp)
            ) {
                items(items.size) { index ->
                    val item = items[index]
                    BasicText(
                        modifier = Modifier
                            .height(36.dp)
                            .fillMaxWidth()
                            .onClick {
                                onValueChange(
                                    TextFieldValue(
                                        text = item,
                                        selection = TextRange(index = item.length),
                                    )
                                )
                            }
                            .wrapContentHeight(),
                        text = item,
                        style = TextStyle(
                            fontSize = 12.sp,
                            color = Color.Gray,
                        ),
                    )
                }
            }
        }
    }
}
