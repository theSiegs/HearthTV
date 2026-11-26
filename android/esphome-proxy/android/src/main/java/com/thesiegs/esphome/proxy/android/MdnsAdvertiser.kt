package com.thesiegs.esphome.proxy.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.thesiegs.esphome.proxy.MacAddress
import com.thesiegs.esphome.proxy.Noise
import com.thesiegs.esphome.proxy.ProxyIdentity

/**
 * Publishes `_esphomelib._tcp` the way an ESPHome device does (esphome/components/mdns), so HA's
 * zeroconf discovery offers the tablet under Settings › Devices & services. TXT records: mac
 * (12 lowercase hex digits, HA's unique id), version, friendly_name, platform, board, network,
 * and api_encryption when the API is encrypted.
 */
internal class MdnsAdvertiser(context: Context, private val log: (String) -> Unit) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var listener: NsdManager.RegistrationListener? = null
    private var record: Triple<ProxyIdentity, Int, String>? = null
    private var failures = 0
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private val again = Runnable { record?.let { (i, p, b) -> register(i, p, b) } }

    /**
     * Publishes the record and keeps it published (code review 2026-11-20): again whenever the
     * default network comes up or its addresses change (a boot before Wi-Fi, a new DHCP lease), and
     * after a failed registration, backing off ([com.thesiegs.esphome.proxy.Retry]). Main thread.
     */
    fun start(identity: ProxyIdentity, port: Int, board: String) {
        record = Triple(identity, port, board)
        failures = 0
        register(identity, port, board)
        if (networkCallback == null && connectivity != null) {
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) = soon()
                override fun onLinkPropertiesChanged(network: android.net.Network, lp: android.net.LinkProperties) = soon()
            }
            try {
                connectivity.registerDefaultNetworkCallback(cb)
                networkCallback = cb
            } catch (e: RuntimeException) {
                log("mDNS: can't follow the network (${e.javaClass.simpleName})")
            }
        }
    }

    /** Stops publishing and following the network. */
    fun stop() {
        record = null
        main.removeCallbacks(again)
        networkCallback?.let { cb -> try { connectivity?.unregisterNetworkCallback(cb) } catch (_: RuntimeException) {} }
        networkCallback = null
        unregister()
    }

    /** A network change: register again shortly (the callback comes on another thread; changes come in bursts). */
    private fun soon() {
        main.removeCallbacks(again)
        main.postDelayed(again, 1_500L)
    }

    private fun register(identity: ProxyIdentity, port: Int, board: String) {
        val manager = nsd ?: return log("no NSD service; zeroconf discovery off")
        unregister()
        val info = NsdServiceInfo().apply {
            serviceName = identity.name
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("mac", MacAddress.formatPlain(identity.mac))
            setAttribute("version", identity.esphomeVersion)
            if (identity.friendlyName.isNotEmpty()) setAttribute("friendly_name", identity.friendlyName)
            setAttribute("platform", "Android")
            setAttribute("board", board)
            setAttribute("network", "wifi")
            if (identity.encrypted) setAttribute("api_encryption", Noise.PROTOCOL_NAME)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { failures = 0; log("mDNS: registered ${info.serviceName}") }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                val wait = com.thesiegs.esphome.proxy.Retry.delayMs(failures++)
                log("mDNS: registration failed $errorCode; trying again in ${wait / 1000} s")
                main.post { if (listener === this) listener = null; main.removeCallbacks(again); main.postDelayed(again, wait) }
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        listener = l
        manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    private fun unregister() {
        val l = listener ?: return
        listener = null
        try { nsd?.unregisterService(l) } catch (_: IllegalArgumentException) {}
    }

    companion object {
        const val SERVICE_TYPE = "_esphomelib._tcp"
    }
}
