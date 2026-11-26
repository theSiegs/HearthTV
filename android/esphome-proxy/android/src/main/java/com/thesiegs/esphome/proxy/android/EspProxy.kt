package com.thesiegs.esphome.proxy.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import com.thesiegs.esphome.proxy.Noise
import com.thesiegs.esphome.proxy.ProxyServer

/**
 * What the proxy is called and how HA reaches it. [name] becomes the ESPHome node name and mDNS
 * instance (sanitized to a hostname); [psk] is the base64 32-byte API encryption key, or null
 * for a plaintext API.
 */
data class EspProxyConfig(
    val name: String,
    val friendlyName: String,
    val area: String = "",
    val psk: String? = null,
    val port: Int = ProxyServer.DEFAULT_PORT,
    /** The MAC HA knows this proxy by, when the host keeps one; null: derived from ANDROID_ID. */
    val mac: Long? = null,
)

/**
 * The host app's entry point (docs/design/esphome-proxy.md):
 *
 *     EspProxy.start(context, "kitchen-tablet", "Kitchen tablet", "Kitchen", key)
 *     EspProxy.stop(context)
 *
 * The proxy runs inside [EspProxyService] (or the host's subclass, given as `service`), which the
 * host declares in its manifest. Ask for [requiredPermissions] first and make sure
 * [isLocationEnabled]; without them the proxy still answers HA but reports its scanner FAILED.
 */
object EspProxy {
    /** Starts (or reconfigures) the proxy service. Call while the app is in the foreground. */
    fun start(
        context: Context,
        name: String,
        friendlyName: String,
        area: String = "",
        psk: String? = null,
        service: Class<out EspProxyService> = EspProxyService::class.java,
    ) = start(context, EspProxyConfig(name, friendlyName, area, psk), service)

    fun start(context: Context, config: EspProxyConfig, service: Class<out EspProxyService> = EspProxyService::class.java) {
        config.psk?.let { Noise.decodePsk(it) } // fail here, not in the service, on a bad key
        val intent = Intent(context, service).setAction(EspProxyService.ACTION_START)
        EspProxyService.putConfig(intent, config)
        context.startForegroundService(intent)
    }

    /** Stops the proxy and forgets the config, so a restarted process doesn't bring it back. */
    fun stop(context: Context, service: Class<out EspProxyService> = EspProxyService::class.java) {
        EspProxyService.clearSavedConfig(context)
        context.stopService(Intent(context, service))
    }

    /** A new random encryption key, in the form HA's ESPHome setup asks for. */
    fun generateKey(): String = Noise.generatePsk()

    /**
     * Runtime permissions to request. Scanning without `neverForLocation` needs fine location on
     * every version (Fire OS / Android 11 included); Android 12+ adds BLUETOOTH_SCAN, Android 13+
     * POST_NOTIFICATIONS for the service's notification (optional: the service runs without it).
     */
    fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_SCAN)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    /** The permissions scanning can't do without that aren't granted yet. */
    fun missingPermissions(context: Context): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_SCAN)
    }.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    /** Android returns no BLE scan results while location services are off. */
    fun isLocationEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 28) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
            return lm.isLocationEnabled
        }
        @Suppress("DEPRECATION")
        return Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF) !=
            Settings.Secure.LOCATION_MODE_OFF
    }
}
