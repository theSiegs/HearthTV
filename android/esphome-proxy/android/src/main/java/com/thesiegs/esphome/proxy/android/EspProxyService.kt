package com.thesiegs.esphome.proxy.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * A ready-made foreground service that runs the proxy. The host declares it (or a subclass that
 * overrides [buildNotification]) in its manifest with
 * `android:foregroundServiceType="connectedDevice|location"` and starts it through [EspProxy.start].
 * The config is kept in private preferences so a sticky restart brings the proxy back as it was.
 */
open class EspProxyService : Service() {
    private var runtime: EspProxyRuntime? = null
    private var current: EspProxyConfig? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = intent?.let(::readConfig)?.also { saveConfig(this, it) } ?: loadConfig(this)
        if (config == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        if (config != current) {
            runtime?.stop()
            current = config
            runtime = EspProxyRuntime(this, config).also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runtime?.stop()
        runtime = null
        current = null
        super.onDestroy()
    }

    /** The ongoing notification Android requires; override for the host's own words and icon. */
    open fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Bluetooth for Home Assistant", NotificationManager.IMPORTANCE_MIN),
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Sharing Bluetooth with Home Assistant")
            .setContentText("Home Assistant hears nearby Bluetooth devices through this tablet.")
            .setOngoing(true)
            .build()
    }

    private fun goForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT < 29) {
            startForeground(NOTIFICATION_ID, notification)
            return
        }
        // connectedDevice always; location too when it's granted, so scan results keep coming
        // while the app is in the background (Android 10+).
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        try {
            startForeground(NOTIFICATION_ID, notification, types)
        } catch (e: RuntimeException) {
            // Android 14+ refuses a type whose permission is missing (or location from the
            // background); fall back to connectedDevice alone.
            Log.w(EspProxyRuntime.TAG, "foreground as $types refused: ${e.message}")
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }
    }

    companion object {
        const val ACTION_START = "com.thesiegs.esphome.proxy.START"
        const val CHANNEL_ID = "esphome_proxy"
        const val NOTIFICATION_ID = 6053
        private const val PREFS = "esphome_proxy"
        private const val K_NAME = "name"
        private const val K_FRIENDLY = "friendly_name"
        private const val K_AREA = "area"
        private const val K_PSK = "psk"
        private const val K_PORT = "port"

        internal fun putConfig(intent: Intent, c: EspProxyConfig) {
            intent.putExtra(K_NAME, c.name).putExtra(K_FRIENDLY, c.friendlyName).putExtra(K_AREA, c.area)
                .putExtra(K_PSK, c.psk).putExtra(K_PORT, c.port)
        }

        private fun readConfig(intent: Intent): EspProxyConfig? {
            val name = intent.getStringExtra(K_NAME) ?: return null
            return EspProxyConfig(
                name,
                intent.getStringExtra(K_FRIENDLY) ?: name,
                intent.getStringExtra(K_AREA) ?: "",
                intent.getStringExtra(K_PSK),
                intent.getIntExtra(K_PORT, com.thesiegs.esphome.proxy.ProxyServer.DEFAULT_PORT),
            )
        }

        /** The key sits in the app's private preferences, like an ESPHome device keeps it in flash. */
        private fun saveConfig(context: Context, c: EspProxyConfig) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(K_NAME, c.name).putString(K_FRIENDLY, c.friendlyName).putString(K_AREA, c.area)
                .putString(K_PSK, c.psk).putInt(K_PORT, c.port).apply()
        }

        private fun loadConfig(context: Context): EspProxyConfig? {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val name = p.getString(K_NAME, null) ?: return null
            return EspProxyConfig(
                name,
                p.getString(K_FRIENDLY, name) ?: name,
                p.getString(K_AREA, "") ?: "",
                p.getString(K_PSK, null),
                p.getInt(K_PORT, com.thesiegs.esphome.proxy.ProxyServer.DEFAULT_PORT),
            )
        }

        internal fun clearSavedConfig(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
