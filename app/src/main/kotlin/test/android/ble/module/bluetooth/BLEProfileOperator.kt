package test.android.ble.module.bluetooth

import android.bluetooth.BluetoothGatt
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import test.android.ble.util.android.GattException
import test.android.ble.util.android.GattUtil
import java.util.Queue
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

internal class BLEProfileOperator(
    private val scope: CoroutineScope,
    private val performer: Performer,
) {
    internal interface Performer {
        fun perform(operation: Operation)
    }

    sealed interface Broadcast {
        class OnWriteCharacteristic(
            val service: UUID,
            val characteristic: UUID,
            val bytes: ByteArray,
        ) : Broadcast
        class OnWriteDescriptor(
            val service: UUID,
            val characteristic: UUID,
            val descriptor: UUID,
            val bytes: ByteArray,
        ) : Broadcast
        class OnReadCharacteristic(
            val service: UUID,
            val characteristic: UUID,
            val bytes: ByteArray,
        ) : Broadcast
        class OnChangeCharacteristic(
            val service: UUID,
            val characteristic: UUID,
            val bytes: ByteArray,
        ) : Broadcast
        class OnServicesDiscovered(
            val services: List<UUID>, // todo characteristics, descriptors
        ) : Broadcast
        class OnSetCharacteristicNotification(
            val service: UUID,
            val characteristic: UUID,
            val value: Boolean,
        ) : Broadcast
    }

    sealed interface Operation {
        class WriteCharacteristic(
            val service: UUID,
            val characteristic: UUID,
            val bytes: ByteArray,
        ) : Operation
        class WriteDescriptor(
            val service: UUID,
            val characteristic: UUID,
            val descriptor: UUID,
            val bytes: ByteArray,
        ) : Operation
        class ReadCharacteristic(
            val service: UUID,
            val characteristic: UUID,
        ) : Operation
        class SetCharacteristicNotification(
            val service: UUID,
            val characteristic: UUID,
            val value: Boolean,
        ) : Operation
    }

    sealed interface Event {
        object OnReady : Event
        object OnOperating : Event
    }

    private val operations: Queue<Operation> = ConcurrentLinkedQueue()

    fun onWriteDescriptor(
        service: UUID,
        characteristic: UUID,
        descriptor: UUID,
        bytes: ByteArray,
    ) {
        Log.d(TAG, "on write $descriptor of $service/$characteristic...")
        val state = BLEGattService.state.value
        if (state !is BLEGattService.State.Connected) TODO("on write $descriptor of $service/$characteristic state: $state")
        val operation = Operation.WriteDescriptor(
            service = service,
            characteristic = characteristic,
            descriptor = descriptor,
            bytes = bytes,
        )
        operations.add(operation)
        if (state.type != BLEGattService.State.Connected.Type.READY) return
        perform()
    }

    fun onWriteCharacteristic(
        service: UUID,
        characteristic: UUID,
        bytes: ByteArray,
    ) {
        Log.d(TAG, "on write $service/$characteristic...")
        val state = BLEGattService.state.value
        if (state !is BLEGattService.State.Connected) TODO("on write $service/$characteristic state: $state")
        val operation = Operation.WriteCharacteristic(
            service = service,
            characteristic = characteristic,
            bytes = bytes,
        )
        operations.add(operation)
        if (state.type != BLEGattService.State.Connected.Type.READY) return
        perform()
    }

    fun onReadCharacteristic(
        service: UUID,
        characteristic: UUID,
    ) {
        Log.d(TAG, "on read $service/$characteristic...")
        val state = BLEGattService.state.value
        if (state !is BLEGattService.State.Connected) TODO("on read $service/$characteristic state: $state")
        val operation = Operation.ReadCharacteristic(
            service = service,
            characteristic = characteristic,
        )
        operations.add(operation)
        if (state.type != BLEGattService.State.Connected.Type.READY) return
        perform()
    }

    fun onSetCharacteristicNotification(
        service: UUID,
        characteristic: UUID,
        value: Boolean,
    ) {
        Log.d(TAG, "on set $service/$characteristic notification...")
        val state = BLEGattService.state.value
        if (state !is BLEGattService.State.Connected) TODO("on set $service/$characteristic notification state: $state")
        val operation = Operation.SetCharacteristicNotification(
            service = service,
            characteristic = characteristic,
            value = value,
        )
        operations.add(operation)
        if (state.type != BLEGattService.State.Connected.Type.READY) return
        perform()
    }

    fun perform() {
        val state = BLEGattService.state.value
        if (state !is BLEGattService.State.Connected) TODO("perform operations state: $state")
        val operation = operations.poll()
        if (operation == null) {
            when (state.type) {
                BLEGattService.State.Connected.Type.READY -> {
                    Log.d(TAG, "All operations already performed.")
                    return
                }
                BLEGattService.State.Connected.Type.OPERATING -> {
                    Log.d(TAG, "All operations performed.")
                    scope.launch {
                        _event.emit(Event.OnReady)
                    }
                    return
                }
                else -> TODO("State: $state. Type: ${state.type}. But no operation!")
            }
        }
        when (state.type) {
            BLEGattService.State.Connected.Type.READY -> {
                Log.d(TAG, "Start perform operations...")
                scope.launch {
                    _event.emit(Event.OnOperating)
                }
            }
            BLEGattService.State.Connected.Type.OPERATING -> {
                // noop
            }
            else -> TODO("perform operations state type: ${state.type}")
        }
        performer.perform(operation)
    }

    companion object {
        private const val TAG = "[Profile]"

        private val _event = MutableSharedFlow<Event>()
        val event = _event.asSharedFlow()
    }
}
