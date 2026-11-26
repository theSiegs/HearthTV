package com.thesiegs.esphome.proxy

/**
 * Message ids of the ESPHome native API, from esphome/components/api/api.proto (option `(id)`),
 * as shipped in aioesphomeapi 46.30.1 (api_pb2 + core.MESSAGE_TYPE_TO_PROTO, API 1.19 client).
 * Only the messages a passive Bluetooth proxy sees are listed; anything else is ignored.
 */
object MessageType {
    const val HELLO_REQUEST = 1
    const val HELLO_RESPONSE = 2
    /** Called ConnectRequest/Response before ESPHome 2025.x; same ids. */
    const val AUTHENTICATION_REQUEST = 3
    const val AUTHENTICATION_RESPONSE = 4
    const val DISCONNECT_REQUEST = 5
    const val DISCONNECT_RESPONSE = 6
    const val PING_REQUEST = 7
    const val PING_RESPONSE = 8
    const val DEVICE_INFO_REQUEST = 9
    const val DEVICE_INFO_RESPONSE = 10
    const val LIST_ENTITIES_REQUEST = 11
    const val LIST_ENTITIES_DONE_RESPONSE = 19
    const val SUBSCRIBE_STATES_REQUEST = 20
    const val SUBSCRIBE_LOGS_REQUEST = 28
    const val SUBSCRIBE_HOMEASSISTANT_SERVICES_REQUEST = 34
    const val GET_TIME_REQUEST = 36
    const val GET_TIME_RESPONSE = 37
    const val SUBSCRIBE_HOME_ASSISTANT_STATES_REQUEST = 38
    const val SUBSCRIBE_BLUETOOTH_LE_ADVERTISEMENTS_REQUEST = 66
    const val SUBSCRIBE_BLUETOOTH_CONNECTIONS_FREE_REQUEST = 80
    const val BLUETOOTH_CONNECTIONS_FREE_RESPONSE = 81
    const val UNSUBSCRIBE_BLUETOOTH_LE_ADVERTISEMENTS_REQUEST = 87
    const val BLUETOOTH_LE_RAW_ADVERTISEMENTS_RESPONSE = 93
    const val NOISE_ENCRYPTION_SET_KEY_REQUEST = 124
    const val NOISE_ENCRYPTION_SET_KEY_RESPONSE = 125
    const val BLUETOOTH_SCANNER_STATE_RESPONSE = 126
    const val BLUETOOTH_SCANNER_SET_MODE_REQUEST = 127
}

/** DeviceInfoResponse.bluetooth_proxy_feature_flags (aioesphomeapi model.BluetoothProxyFeature). */
object BluetoothProxyFeature {
    const val PASSIVE_SCAN = 1 shl 0
    const val ACTIVE_CONNECTIONS = 1 shl 1
    const val REMOTE_CACHING = 1 shl 2
    const val PAIRING = 1 shl 3
    const val CACHE_CLEARING = 1 shl 4
    const val RAW_ADVERTISEMENTS = 1 shl 5
    const val STATE_AND_MODE = 1 shl 6
}

/** SubscribeBluetoothLEAdvertisementsRequest.flags (aioesphomeapi BluetoothProxySubscriptionFlag). */
object BluetoothSubscriptionFlag {
    const val RAW_ADVERTISEMENTS = 1 shl 0
}

/** api.proto BluetoothScannerState. */
enum class ScannerState(val wire: Int) { IDLE(0), STARTING(1), RUNNING(2), FAILED(3), STOPPING(4), STOPPED(5) }

/** api.proto BluetoothScannerMode. */
enum class ScannerMode(val wire: Int) {
    PASSIVE(0), ACTIVE(1);

    companion object {
        fun of(wire: Int) = entries.firstOrNull { it.wire == wire } ?: PASSIVE
    }
}

/** A message as it travels inside a frame: its id and its protobuf body. */
class ApiMessage(val type: Int, val payload: ByteArray = ByteArray(0)) {
    override fun toString() = "ApiMessage(type=$type, ${payload.size} bytes)"
}

/** HelloRequest (1): client_info=1, api_version_major=2, api_version_minor=3. */
data class HelloRequest(val clientInfo: String, val apiVersionMajor: Int, val apiVersionMinor: Int) {
    fun encode() = ApiMessage(
        MessageType.HELLO_REQUEST,
        ProtoWriter().string(1, clientInfo).uint32(2, apiVersionMajor).uint32(3, apiVersionMinor).toByteArray(),
    )

    companion object {
        fun decode(data: ByteArray): HelloRequest {
            var info = ""; var major = 0; var minor = 0
            for (f in ProtoReader.fields(data)) when (f.number) {
                1 -> info = f.string
                2 -> major = f.int
                3 -> minor = f.int
            }
            return HelloRequest(info, major, minor)
        }
    }
}

/** HelloResponse (2): api_version_major=1, api_version_minor=2, server_info=3, name=4. */
data class HelloResponse(val apiVersionMajor: Int, val apiVersionMinor: Int, val serverInfo: String, val name: String) {
    fun encode() = ApiMessage(
        MessageType.HELLO_RESPONSE,
        ProtoWriter().uint32(1, apiVersionMajor).uint32(2, apiVersionMinor)
            .string(3, serverInfo).string(4, name).toByteArray(),
    )

    companion object {
        fun decode(data: ByteArray): HelloResponse {
            var major = 0; var minor = 0; var info = ""; var name = ""
            for (f in ProtoReader.fields(data)) when (f.number) {
                1 -> major = f.int
                2 -> minor = f.int
                3 -> info = f.string
                4 -> name = f.string
            }
            return HelloResponse(major, minor, info, name)
        }
    }
}

/**
 * DeviceInfoResponse (10). Field numbers from api.proto: uses_password=1, name=2, mac_address=3,
 * esphome_version=4, compilation_time=5, model=6, project_name=8, project_version=9,
 * legacy_bluetooth_proxy_version=11, manufacturer=12, friendly_name=13, bluetooth_proxy_feature_flags=15,
 * suggested_area=16, bluetooth_mac_address=18, api_encryption_supported=19.
 */
data class DeviceInfoResponse(
    val name: String,
    val macAddress: String,
    val esphomeVersion: String,
    val compilationTime: String = "",
    val model: String = "",
    val manufacturer: String = "",
    val friendlyName: String = "",
    val suggestedArea: String = "",
    val projectName: String = "",
    val projectVersion: String = "",
    val legacyBluetoothProxyVersion: Int = 0,
    val bluetoothProxyFeatureFlags: Int = 0,
    val bluetoothMacAddress: String = "",
    val apiEncryptionSupported: Boolean = false,
) {
    fun encode() = ApiMessage(
        MessageType.DEVICE_INFO_RESPONSE,
        ProtoWriter()
            .string(2, name)
            .string(3, macAddress)
            .string(4, esphomeVersion)
            .string(5, compilationTime)
            .string(6, model)
            .string(8, projectName)
            .string(9, projectVersion)
            .uint32(11, legacyBluetoothProxyVersion)
            .string(12, manufacturer)
            .string(13, friendlyName)
            .uint32(15, bluetoothProxyFeatureFlags)
            .string(16, suggestedArea)
            .string(18, bluetoothMacAddress)
            .bool(19, apiEncryptionSupported)
            .toByteArray(),
    )

    companion object {
        fun decode(data: ByteArray): DeviceInfoResponse {
            var r = DeviceInfoResponse("", "", "")
            for (f in ProtoReader.fields(data)) r = when (f.number) {
                2 -> r.copy(name = f.string)
                3 -> r.copy(macAddress = f.string)
                4 -> r.copy(esphomeVersion = f.string)
                5 -> r.copy(compilationTime = f.string)
                6 -> r.copy(model = f.string)
                8 -> r.copy(projectName = f.string)
                9 -> r.copy(projectVersion = f.string)
                11 -> r.copy(legacyBluetoothProxyVersion = f.int)
                12 -> r.copy(manufacturer = f.string)
                13 -> r.copy(friendlyName = f.string)
                15 -> r.copy(bluetoothProxyFeatureFlags = f.int)
                16 -> r.copy(suggestedArea = f.string)
                18 -> r.copy(bluetoothMacAddress = f.string)
                19 -> r.copy(apiEncryptionSupported = f.bool)
                else -> r
            }
            return r
        }
    }
}

/**
 * One BluetoothLERawAdvertisement: address=1 (uint64, the 48-bit address big-endian in the low
 * bits), rssi=2 (sint32), address_type=3 (uint32: 0 public, 1 random), data=4 (the raw AD bytes).
 */
class RawAdvertisement(val address: Long, val rssi: Int, val addressType: Int, val data: ByteArray) {
    fun encode(): ByteArray =
        ProtoWriter().uint64(1, address).sint32(2, rssi).uint32(3, addressType).bytes(4, data).toByteArray()

    override fun equals(other: Any?) = other is RawAdvertisement && other.address == address &&
        other.rssi == rssi && other.addressType == addressType && other.data.contentEquals(data)

    override fun hashCode() = address.hashCode() * 31 + rssi

    override fun toString() = "RawAdvertisement(${MacAddress.format(address)}, rssi=$rssi, type=$addressType, ${data.size} bytes)"

    companion object {
        /**
         * Android hands over the advertisement and scan response concatenated and zero-padded to
         * 62 bytes. Keep the whole AD structures and drop the padding (a zero length ends the
         * data, as in the Core spec), so HA parses exactly what was on the air.
         */
        fun trimPadding(bytes: ByteArray?): ByteArray {
            if (bytes == null) return ByteArray(0)
            var i = 0
            while (i < bytes.size) {
                val len = bytes[i].toInt() and 0xFF
                if (len == 0 || i + 1 + len > bytes.size) break
                i += 1 + len
            }
            return bytes.copyOf(i)
        }

        fun decode(data: ByteArray): RawAdvertisement {
            var address = 0L; var rssi = 0; var type = 0; var bytes = ByteArray(0)
            for (f in ProtoReader.fields(data)) when (f.number) {
                1 -> address = f.value
                2 -> rssi = f.sint32
                3 -> type = f.int
                4 -> bytes = f.bytes ?: ByteArray(0)
            }
            return RawAdvertisement(address, rssi, type, bytes)
        }
    }
}

/** BluetoothLERawAdvertisementsResponse (93): advertisements=1 (repeated). */
object RawAdvertisementsResponse {
    fun encode(ads: List<RawAdvertisement>): ApiMessage {
        val w = ProtoWriter()
        for (ad in ads) w.message(1, ad.encode())
        return ApiMessage(MessageType.BLUETOOTH_LE_RAW_ADVERTISEMENTS_RESPONSE, w.toByteArray())
    }

    fun decode(data: ByteArray): List<RawAdvertisement> =
        ProtoReader.fields(data).filter { it.number == 1 }.map { RawAdvertisement.decode(it.bytes!!) }
}

/** BluetoothScannerStateResponse (126): state=1, mode=2, configured_mode=3. */
data class ScannerStateResponse(val state: ScannerState, val mode: ScannerMode, val configuredMode: ScannerMode) {
    fun encode() = ApiMessage(
        MessageType.BLUETOOTH_SCANNER_STATE_RESPONSE,
        ProtoWriter().enum(1, state.wire).enum(2, mode.wire).enum(3, configuredMode.wire).toByteArray(),
    )

    companion object {
        fun decode(data: ByteArray): ScannerStateResponse {
            var state = 0; var mode = 0; var configured = 0
            for (f in ProtoReader.fields(data)) when (f.number) {
                1 -> state = f.int
                2 -> mode = f.int
                3 -> configured = f.int
            }
            return ScannerStateResponse(
                ScannerState.entries.firstOrNull { it.wire == state } ?: ScannerState.IDLE,
                ScannerMode.of(mode),
                ScannerMode.of(configured),
            )
        }
    }
}

/** Reads the single uint32/enum field 1 that several requests carry (flags, mode). */
internal fun firstUint(data: ByteArray, field: Int = 1): Int =
    ProtoReader.fields(data).lastOrNull { it.number == field }?.int ?: 0

/** Formats and parses the 48-bit addresses ESPHome and HA pass around. */
object MacAddress {
    /** "AA:BB:CC:DD:EE:FF", as DeviceInfoResponse carries it (ESPHome get_mac_address_pretty). */
    fun format(address: Long): String =
        (5 downTo 0).joinToString(":") { "%02X".format((address ushr (8 * it)) and 0xFF) }

    /** "aabbccddeeff", as the Noise server hello and the mDNS `mac` TXT record carry it. */
    fun formatPlain(address: Long): String =
        (5 downTo 0).joinToString("") { "%02x".format((address ushr (8 * it)) and 0xFF) }

    /** Parses "AA:BB:CC:DD:EE:FF" (Android's BluetoothDevice.getAddress()) into the low 48 bits. */
    fun parse(text: String): Long {
        val parts = text.split(':')
        require(parts.size == 6) { "not a MAC address: $text" }
        var v = 0L
        for (p in parts) v = (v shl 8) or p.toLong(16).also { require(it in 0..255) }
        return v
    }

    fun fromBytes(b: ByteArray): Long {
        require(b.size == 6)
        var v = 0L
        for (x in b) v = (v shl 8) or (x.toLong() and 0xFF)
        return v
    }
}
