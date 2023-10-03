package test.android.ble.module.bluetooth

import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import test.android.ble.util.android.scanStart
import test.android.ble.util.android.scanStop
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.absoluteValue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal class BLEScanner(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onScanResult: (ScanResult) -> Unit,
    private val timeoutUntil: () -> Boolean,
) {
    private val scanCallback = AtomicReference<ScanCallback?>(null)
    private var timeLastResult = System.currentTimeMillis().milliseconds
    private inner class InternalScanCallback : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val currentHash = getHash()
            val startedCallback = scanCallback.get()
            if (startedCallback == null) {
                Log.d(TAG, "No scan. So abort $currentHash.")
                return
            }
            val startedHash = startedCallback.getHash()
            if (currentHash != startedHash) {
                Log.d(TAG, "No $currentHash scan. But $startedHash started.")
                return
            }
            if (result == null) {
                Log.w(TAG, "No scan result!")
                return
            }
            timeLastResult = System.currentTimeMillis().milliseconds
            Log.d(TAG, "ScanResult: ct [$callbackType] - ${result.device.address}/${result.device.name}")
            onScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            error("on batch scan results not supported!")
        }

        override fun onScanFailed(errorCode: Int) {
            error("on scan failed not supported!")
        }
    }

    private fun restartByTimeout(hash: String, scanSettings: ScanSettings) {
        scope.launch {
            val timeMax = 3.seconds
            val timeDelay = 100.milliseconds
            withContext(Dispatchers.Default) {
                while (scanCallback.get()?.getHash() == hash && timeoutUntil()) {
                    val timeNow = System.currentTimeMillis().milliseconds
                    val diff = timeNow - timeLastResult
                    if (diff > timeMax) {
                        Log.w(TAG, "$diff have passed since the BLE device was last listened to. No new results, so let's stop scanning and start again.")
                        stop()
                        start(scanSettings)
                        break
                    }
                    if (diff.inWholeMilliseconds - diff.inWholeSeconds * 1_000 < timeDelay.inWholeMilliseconds) {
                        if (diff.inWholeSeconds > 0) {
                            Log.i(TAG, "I haven't heard a single BLE device for ${diff.inWholeSeconds} seconds....")
                        }
                    }
                    delay(timeDelay)
                }
            }
        }
    }

    fun start(scanSettings: ScanSettings) = synchronized(BLEScanner::class.java) {
        val callback = InternalScanCallback()
        if (scanCallback.getAndSet(callback) != null) TODO("Scan already started. But callback exists!")
        context.scanStart(callback, scanSettings)
        timeLastResult = System.currentTimeMillis().milliseconds
        restartByTimeout(hash = callback.getHash(), scanSettings)
    }

    fun stop() = synchronized(BLEScanner::class.java) {
        val callback = scanCallback.getAndSet(null)
        if (callback == null) {
            Log.d(TAG, "Scan already stopped.")
            return
        }
        context.scanStop(callback)
    }

    companion object {
        private const val TAG = "[BLE|Scanner]"

        private fun ScanCallback.getHash(): String {
            return String.format("%04d", hashCode().absoluteValue % 9999)
        }
    }
}
