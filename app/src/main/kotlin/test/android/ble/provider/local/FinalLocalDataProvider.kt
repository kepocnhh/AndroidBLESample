package test.android.ble.provider.local

import android.content.Context
import test.android.ble.BuildConfig

internal class FinalLocalDataProvider(context: Context) : LocalDataProvider {
    private val preferences = context.getSharedPreferences(BuildConfig.APPLICATION_ID, Context.MODE_PRIVATE)

    override var address: String?
        get() {
            return preferences.getString("address", null)
        }
        set(value) {
            preferences
                .edit()
                .putString("address", value)
                .commit()
        }
}
