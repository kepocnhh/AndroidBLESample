package test.android.ble.util

internal sealed interface Optional<out T : Any> {
    class Some<T : Any>(val value: T) : Optional<T>
    object None : Optional<Nothing>
}
