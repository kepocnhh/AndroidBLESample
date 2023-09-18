package test.android.ble.module.app

import test.android.ble.provider.Contexts
import test.android.ble.provider.local.LocalDataProvider

internal data class Injection(
    val local: LocalDataProvider,
    val contexts: Contexts,
)
