package test.android.ble.util.android

import android.content.Context
import android.location.LocationManager

internal class LocException(val error: Error) : Exception() {
    enum class Error {
        DISABLED,
    }
}

internal fun Context.checkProvider(provider: String = LocationManager.GPS_PROVIDER) {
    if (!getSystemService(LocationManager::class.java).isProviderEnabled(provider)) {
        throw LocException(LocException.Error.DISABLED)
    }
}
