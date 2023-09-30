package test.android.ble.util.android

import android.content.Context
import android.location.LocationManager

internal class LocException(val error: Error) : Exception("Error: ${error.name}") {
    enum class Error {
        DISABLED,
    }
}

internal fun Context.isLocationEnabled(provider: String = LocationManager.GPS_PROVIDER): Boolean {
    return getSystemService(LocationManager::class.java).isProviderEnabled(provider)
}

internal fun Context.checkProvider(provider: String = LocationManager.GPS_PROVIDER) {
    if (!getSystemService(LocationManager::class.java).isProviderEnabled(provider)) {
        throw LocException(LocException.Error.DISABLED)
    }
}
