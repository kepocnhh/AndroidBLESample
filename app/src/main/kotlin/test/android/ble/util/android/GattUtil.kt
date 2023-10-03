package test.android.ble.util.android

import android.bluetooth.BluetoothGatt
import java.util.UUID

internal class GattException(val type: Type): Exception() {
    enum class Type {
        WRITING_WAS_NOT_INITIATED,
        READING_WAS_NOT_INITIATED,
        DISCONNECTING_BY_TIMEOUT,
    }
}

internal object GattUtil {
    fun setCharacteristicNotification(
        gatt: BluetoothGatt,
        service: UUID,
        characteristic: UUID,
        value: Boolean,
    ) {
        val service = gatt.getService(service)
            ?: TODO("No service $service!")
        val characteristic = service.getCharacteristic(characteristic)
            ?: TODO("No characteristic $characteristic!")
        if (!gatt.setCharacteristicNotification(characteristic, value)) {
            TODO("Set characteristic notification $value error!")
        }
    }

    fun readCharacteristic(
        gatt: BluetoothGatt,
        service: UUID,
        characteristic: UUID,
    ) {
        val service = gatt.getService(service)
            ?: TODO("No service $service!")
        val characteristic = service.getCharacteristic(characteristic)
            ?: TODO("No characteristic $characteristic!")
        if (!gatt.readCharacteristic(characteristic)) {
            throw GattException(GattException.Type.READING_WAS_NOT_INITIATED)
        }
    }

    fun writeCharacteristic(
        gatt: BluetoothGatt,
        service: UUID,
        characteristic: UUID,
        bytes: ByteArray,
    ) {
        val service = gatt.getService(service)
            ?: TODO("No service $service!")
        val characteristic = service.getCharacteristic(characteristic)
            ?: TODO("No characteristic $characteristic!")
        if (!characteristic.setValue(bytes)) {
            TODO("Characteristic set value error!")
        }
        if (!gatt.writeCharacteristic(characteristic)) {
            throw GattException(GattException.Type.WRITING_WAS_NOT_INITIATED)
        }
    }

    fun writeDescriptor(
        gatt: BluetoothGatt,
        service: UUID,
        characteristic: UUID,
        descriptor: UUID,
        bytes: ByteArray,
    ) {
        val service = gatt.getService(service)
            ?: TODO("No service $service!")
        val characteristic = service.getCharacteristic(characteristic)
            ?: TODO("No characteristic ${service.uuid}/$characteristic!")
        val descriptor = characteristic.getDescriptor(descriptor)
            ?: TODO("No descriptor ${service.uuid}/${characteristic.uuid}/$descriptor!")
        if (!descriptor.setValue(bytes)) {
            TODO("Descriptor set value error!")
        }
        if (!gatt.writeDescriptor(descriptor)) {
            TODO("GATT write D error!")
        }
    }
}
