package com.thesiegs.esphome.proxy.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import com.thesiegs.esphome.proxy.MacAddress
import com.thesiegs.esphome.proxy.RawAdvertisement
import com.thesiegs.esphome.proxy.ScannerState

/**
 * Unfiltered low-latency BLE scanning that hands every advertisement over raw. Runs only between
 * [start] and [stop] (the runtime calls them as HA clients subscribe and leave). Everything runs on
 * the main looper.
 *
 * Android's limits it works around: a scan running 30 minutes is silently downgraded to
 * opportunistic, so it restarts every [RESTART_MS]; a failed start (often "too many starts") is
 * retried after [RETRY_MS]; Bluetooth or location switched off reports FAILED and resumes when
 * they come back.
 *
 * With the screen off Android pauses an unfiltered scan, so then it scans for the advertisers
 * presence tracking uses ([SCREEN_OFF_FILTERS]: phones, watches, tags and beacons by maker and
 * service), and goes back to everything when the screen comes on (owner 2026-11-20).
 */
internal class BleScanner(
    private val context: Context,
    private val onAdvertisement: (RawAdvertisement) -> Unit,
    private val onState: (ScannerState) -> Unit,
    private val log: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var wanted = false
    private var scanning = false
    private var receiverRegistered = false
    private var filtered = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = deliver(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::deliver)

        override fun onScanFailed(errorCode: Int) {
            log("scan failed: $errorCode")
            scanning = false
            onState(ScannerState.FAILED)
            handler.removeCallbacks(restart)
            handler.postDelayed(restart, RETRY_MS)
        }
    }

    private val restart = Runnable {
        stopScan()
        if (wanted) startScan()
    }

    /** Bluetooth or location switched: try again (or report why not). */
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (!wanted) return
            // The screen's on/off only matters when it changes which scan runs.
            if ((intent.action == Intent.ACTION_SCREEN_ON || intent.action == Intent.ACTION_SCREEN_OFF) && screenOff() == filtered) return
            handler.removeCallbacks(restart)
            handler.postDelayed(restart, 1_000)
        }
    }

    fun start() {
        if (wanted) return
        wanted = true
        if (!receiverRegistered) {
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
                addAction(LocationManager.MODE_CHANGED_ACTION)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            receiverRegistered = true
        }
        startScan()
    }

    fun stop() {
        if (!wanted) return
        wanted = false
        handler.removeCallbacks(restart)
        if (receiverRegistered) {
            try { context.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
            receiverRegistered = false
        }
        onState(ScannerState.STOPPING)
        stopScan()
        onState(ScannerState.IDLE)
    }

    private fun screenOff(): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive == false

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val a = adapter
        val problem = when {
            a == null -> "no Bluetooth adapter"
            !a.isEnabled -> "Bluetooth is off"
            EspProxy.missingPermissions(context).isNotEmpty() -> "missing ${EspProxy.missingPermissions(context)}"
            !EspProxy.isLocationEnabled(context) -> "location is off (Android returns no scan results without it)"
            else -> null
        }
        val scanner = a?.bluetoothLeScanner
        if (problem != null || scanner == null) {
            log("can't scan: ${problem ?: "no LE scanner"}")
            onState(ScannerState.FAILED)
            return // the receiver retries when Bluetooth or location comes back
        }
        onState(ScannerState.STARTING)
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        val off = screenOff()
        try {
            scanner.startScan(if (off) SCREEN_OFF_FILTERS else null, settings, callback)
            filtered = off
            log(if (off) "scanning for presence devices (screen off)" else "scanning everything")
        } catch (e: SecurityException) {
            log("scan refused: ${e.message}")
            onState(ScannerState.FAILED)
            return
        } catch (e: IllegalStateException) {
            // The adapter went off between the check and the call.
            log("scan refused: ${e.message}")
            onState(ScannerState.FAILED)
            return
        }
        scanning = true
        onState(ScannerState.RUNNING)
        handler.removeCallbacks(restart)
        handler.postDelayed(restart, RESTART_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        }
    }

    private fun deliver(result: ScanResult) {
        val address = try { MacAddress.parse(result.device.address) } catch (_: IllegalArgumentException) { return }
        onAdvertisement(RawAdvertisement(address, result.rssi, addressType(result), RawAdvertisement.trimPadding(result.scanRecord?.bytes)))
    }

    /**
     * ESPHome's address_type: 0 public, 1 random. Android reports it only from API 35 (Android 15); before
     * that it's unknown and we send 0 (public). HA uses it only to open connections, which this
     * proxy doesn't offer, so presence and RSSI are unaffected.
     */
    private fun addressType(result: ScanResult): Int =
        if (Build.VERSION.SDK_INT >= 35 && result.device.addressType == BluetoothDevice.ADDRESS_TYPE_RANDOM) 1 else 0

    companion object {
        const val RESTART_MS = 25 * 60_000L
        const val RETRY_MS = 30_000L

        /** Bluetooth SIG company ids of the makers whose phones, watches and tags presence tracking sees. */
        private val MAKERS = intArrayOf(
            0x004C, // Apple (iBeacon, phones, watches, AirTags)
            0x00E0, // Google
            0x0075, // Samsung
            0x0006, // Microsoft
            0x0087, // Garmin
            0x0157, // Huami / Amazfit
            0x038F, // Xiaomi
            0x0171, // Amazon
            0x0059, // Nordic (many tags and beacons)
            0x02E5, // Espressif (ESP32 beacons)
            0x0499, // Ruuvi
        )

        /**
         * 16-bit services presence trackers and beacons advertise, by service UUID or service data. One list: add a
         * service here and the screen-off scan looks for it both ways.
         */
        private val SERVICES = intArrayOf(
            0xFEAA, // Eddystone; Google Find Hub trackers (frame 0x40/0x41, the Find Hub Network Accessory spec)
            0xFE2C, // Google Fast Pair
            0xFEF3, // Google (Android phones: Nearby, Quick Share)
            0xFCF1, // Google (Android phones and watches)
            0xFD44, // Apple
            0xFCB2, // Detecting Unwanted Location Trackers (Apple and Google's shared tracker format)
            0xFD6F, // exposure notification
            0xFEED, 0xFEEC, // Tile
            0xFE95, // Xiaomi
            0xFD5A, // Samsung
            0xFE9F, // Google
            0xFCD2, // BTHome
            0x180F, // battery
            0x181A, // environmental sensing
        )

        private fun uuid16(id: Int) = ParcelUuid.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", id))

        /**
         * What a screen-off scan looks for. Each filter is a real field (Android treats an all-empty
         * filter as unfiltered and pauses it too): a maker's id, or a service by its UUID or its data.
         */
        val SCREEN_OFF_FILTERS: List<ScanFilter> by lazy {
            MAKERS.map { ScanFilter.Builder().setManufacturerData(it, ByteArray(0)).build() } +
                SERVICES.flatMap { id ->
                    listOf(
                        ScanFilter.Builder().setServiceUuid(uuid16(id)).build(),
                        ScanFilter.Builder().setServiceData(uuid16(id), ByteArray(0)).build(),
                    )
                }
        }
    }
}
