package test.android.ble.util.android

import android.content.Context
import android.widget.Toast

internal fun Context.showToast(message: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, message, duration).show()
}
