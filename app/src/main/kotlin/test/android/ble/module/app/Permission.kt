package test.android.ble.module.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class Permission(
    val isGranted: Boolean,
    val requested: Boolean,
)
