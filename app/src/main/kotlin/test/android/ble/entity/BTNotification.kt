package test.android.ble.entity

import java.util.UUID

internal class BTNotification(
    val service: UUID,
    val characteristic: UUID,
    val bytes: ByteArray,
) {
    override fun toString(): String {
        return "BTNotification($service/$characteristic)"
    }
}
