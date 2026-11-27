package com.thesiegs.esphome.proxy

/**
 * What the proxy says about itself. [mac] is the 48-bit device MAC (on Android a stable,
 * locally administered one, since the real Bluetooth MAC is hidden); [bluetoothMac] is the
 * scanner's address HA uses as the advertisement source.
 */
data class ProxyIdentity(
    /** ESPHome node name: lowercase letters, digits and dashes, also the mDNS instance name. */
    val name: String,
    val friendlyName: String,
    val suggestedArea: String = "",
    val mac: Long,
    val bluetoothMac: Long = mac,
    val model: String = "Android",
    val manufacturer: String = "Android",
    /** ESPHome version we claim. HA asks to update a Bluetooth proxy older than its STABLE_BLE_VERSION (2026.5.1 in HA 2026.11). */
    val esphomeVersion: String = ESPHOME_VERSION,
    val projectName: String = "",
    val projectVersion: String = "",
    val encrypted: Boolean = false,
) {
    fun deviceInfo() = DeviceInfoResponse(
        name = name,
        macAddress = MacAddress.format(mac),
        esphomeVersion = esphomeVersion,
        compilationTime = "",
        model = model,
        manufacturer = manufacturer,
        friendlyName = friendlyName,
        suggestedArea = suggestedArea,
        projectName = projectName,
        projectVersion = projectVersion,
        // ESPHome sends 1 for a passive-only proxy (5 with active connections).
        legacyBluetoothProxyVersion = 1,
        bluetoothProxyFeatureFlags = FEATURE_FLAGS,
        bluetoothMacAddress = MacAddress.format(bluetoothMac),
        apiEncryptionSupported = encrypted,
    )

    companion object {
        /**
         * A stable, locally administered unicast MAC from a per-device seed (on Android,
         * ANDROID_ID), for hosts that can't read their real one. SHA-256 of the seed, first six
         * bytes, with the "locally administered" bit set and the multicast bit cleared.
         */
        fun deriveMac(seed: String): Long {
            val d = java.security.MessageDigest.getInstance("SHA-256").digest("esphome-proxy:$seed".toByteArray())
            d[0] = ((d[0].toInt() and 0xFC) or 0x02).toByte()
            return MacAddress.fromBytes(d.copyOf(6))
        }

        /** The scanner's address: base MAC + 2 in the last byte, as an ESP32 numbers its Bluetooth MAC. */
        fun bluetoothMacFor(mac: Long): Long = (mac and 0xFFFFFFFFFF00L) or (((mac and 0xFF) + 2) and 0xFF)

        /** ESPHome node names are hostnames: lowercase letters, digits and dashes, at most 31 long. */
        fun sanitizeName(raw: String): String {
            val s = raw.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }.joinToString("")
                .replace(Regex("-+"), "-").trim('-').take(31).trim('-')
            return s.ifEmpty { "android-proxy" }
        }

        const val ESPHOME_VERSION = "2026.11.0"
        /** Passive scan, raw advertisements, scanner state and mode. No active connections. */
        const val FEATURE_FLAGS = BluetoothProxyFeature.PASSIVE_SCAN or
            BluetoothProxyFeature.RAW_ADVERTISEMENTS or BluetoothProxyFeature.STATE_AND_MODE
        /** API version in our HelloResponse: new enough for feature flags (1.9+) and raw ads. */
        const val API_MAJOR = 1
        const val API_MINOR = 12
    }
}

/**
 * One HA connection's protocol logic, with no sockets and no clock of its own: hand it each
 * decoded message and it returns what to send back and whether the connection should close.
 * The scanner side reaches it through [advertisements] and [scannerState].
 */
class ApiSession(
    private val identity: ProxyIdentity,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    enum class Phase { AWAIT_HELLO, CONNECTED, CLOSED }

    class Result(val replies: List<ApiMessage>, val close: Boolean = false) {
        companion object { val NONE = Result(emptyList()) }
    }

    var phase = Phase.AWAIT_HELLO; private set
    /** True while this client wants raw advertisements; the server scans while any client does. */
    var subscribed = false; private set
    var clientInfo = ""; private set
    private var lastState: ScannerStateResponse? = null

    fun onMessage(msg: ApiMessage): Result {
        if (phase == Phase.CLOSED) return Result.NONE
        if (phase == Phase.AWAIT_HELLO) {
            return when (msg.type) {
                MessageType.HELLO_REQUEST -> {
                    clientInfo = HelloRequest.decode(msg.payload).clientInfo
                    phase = Phase.CONNECTED
                    reply(HelloResponse(ProxyIdentity.API_MAJOR, ProxyIdentity.API_MINOR, serverInfo(), identity.name).encode())
                }
                MessageType.PING_REQUEST -> reply(ApiMessage(MessageType.PING_RESPONSE))
                MessageType.DISCONNECT_REQUEST -> close(ApiMessage(MessageType.DISCONNECT_RESPONSE))
                // ESPHome drops a client that skips the hello.
                else -> close()
            }
        }
        return when (msg.type) {
            MessageType.HELLO_REQUEST -> Result.NONE
            // No password: ESPHome sends nothing back, and aioesphomeapi doesn't wait for it.
            MessageType.AUTHENTICATION_REQUEST -> Result.NONE
            MessageType.DISCONNECT_REQUEST -> close(ApiMessage(MessageType.DISCONNECT_RESPONSE))
            MessageType.DISCONNECT_RESPONSE -> close()
            MessageType.PING_REQUEST -> reply(ApiMessage(MessageType.PING_RESPONSE))
            MessageType.PING_RESPONSE -> Result.NONE
            MessageType.DEVICE_INFO_REQUEST -> reply(identity.deviceInfo().encode())
            MessageType.LIST_ENTITIES_REQUEST -> reply(ApiMessage(MessageType.LIST_ENTITIES_DONE_RESPONSE))
            MessageType.SUBSCRIBE_STATES_REQUEST,
            MessageType.SUBSCRIBE_LOGS_REQUEST,
            MessageType.SUBSCRIBE_HOMEASSISTANT_SERVICES_REQUEST,
            MessageType.SUBSCRIBE_HOME_ASSISTANT_STATES_REQUEST,
            MessageType.GET_TIME_RESPONSE -> Result.NONE
            MessageType.GET_TIME_REQUEST -> reply(
                ApiMessage(MessageType.GET_TIME_RESPONSE, ProtoWriter().fixed32(1, nowEpochSeconds().toInt()).toByteArray()),
            )
            MessageType.SUBSCRIBE_BLUETOOTH_LE_ADVERTISEMENTS_REQUEST -> {
                val flags = firstUint(msg.payload)
                // Only raw advertisements are offered (the feature flags say so); a legacy client
                // asking for parsed ones gets nothing rather than messages it can't read.
                if (flags and BluetoothSubscriptionFlag.RAW_ADVERTISEMENTS != 0) subscribed = true
                Result(listOfNotNull(lastState?.encode()))
            }
            MessageType.UNSUBSCRIBE_BLUETOOTH_LE_ADVERTISEMENTS_REQUEST -> { subscribed = false; Result.NONE }
            MessageType.BLUETOOTH_SCANNER_SET_MODE_REQUEST ->
                // Android's public scan API is always active (it sends scan requests), so the mode
                // can't change; report the truth again.
                Result(listOfNotNull(lastState?.encode()))
            MessageType.SUBSCRIBE_BLUETOOTH_CONNECTIONS_FREE_REQUEST -> reply(
                ApiMessage(MessageType.BLUETOOTH_CONNECTIONS_FREE_RESPONSE, ByteArray(0)),
            )
            MessageType.NOISE_ENCRYPTION_SET_KEY_REQUEST -> reply(
                // The key is the host app's to set, not HA's: refuse (success=false).
                ApiMessage(MessageType.NOISE_ENCRYPTION_SET_KEY_RESPONSE, ByteArray(0)),
            )
            else -> Result.NONE
        }
    }

    /** A batch for this client, or null when it isn't subscribed. */
    fun advertisements(batch: List<RawAdvertisement>): ApiMessage? =
        if (phase == Phase.CONNECTED && subscribed && batch.isNotEmpty()) RawAdvertisementsResponse.encode(batch) else null

    /** Records the scanner's state; returns the message to send if this client is subscribed. */
    fun scannerState(state: ScannerState, mode: ScannerMode = ScannerMode.ACTIVE): ApiMessage? {
        val s = ScannerStateResponse(state, mode, mode)
        lastState = s
        return if (phase == Phase.CONNECTED && subscribed) s.encode() else null
    }

    fun closed() { phase = Phase.CLOSED; subscribed = false }

    private fun serverInfo() = "${identity.name} (ESPHome ${identity.esphomeVersion}-compatible Android proxy)"

    private fun reply(m: ApiMessage) = Result(listOf(m))

    private fun close(vararg last: ApiMessage): Result {
        closed()
        return Result(last.toList(), close = true)
    }
}
