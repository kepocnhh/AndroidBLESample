package test.android.ble.util.compose

import android.os.Build
import android.view.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

internal fun WindowInsets.toPaddings(density: Float) : PaddingValues {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        getInsets(WindowInsets.Type.systemBars()).let {
            PaddingValues(
                bottom = it.bottom.px(density),
                end = it.right.px(density),
                start = it.left.px(density),
                top = it.top.px(density),
            )
        }
    } else {
        PaddingValues(
            bottom = systemWindowInsetBottom.px(density),
            end = systemWindowInsetRight.px(density),
            start = systemWindowInsetLeft.px(density),
            top = systemWindowInsetTop.px(density),
        )
    }
}

@Composable
internal fun WindowInsets.toPaddings(density: Density = LocalDensity.current) : PaddingValues {
    return toPaddings(density = density.density)
}
