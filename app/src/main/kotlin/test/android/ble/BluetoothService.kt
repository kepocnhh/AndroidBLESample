package test.android.ble

import android.app.Service
import android.content.Intent
import android.os.IBinder

internal class BluetoothService : Service() {
    override fun onBind(p0: Intent?): IBinder? {
        return null
    }
}
