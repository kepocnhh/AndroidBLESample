package test.android.ble.provider.local

internal interface LocalDataProvider {
    var address: String?
    var writes: Set<String>
}
