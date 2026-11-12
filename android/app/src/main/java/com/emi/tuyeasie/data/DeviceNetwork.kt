package com.emi.tuyeasie.data

/**
 * What the Tuya cloud says the device can do.
 * [dataPoints] maps data point codes (e.g. "onvif_switch") to their IDs, which differ between models.
 */
data class DeviceCapabilities(
    val dataPoints: Map<String, String>,
    val mac: String?,
    val tuyaLan: Boolean
) {
    val tuyaOnvif: Boolean
        get() = TUYA_ONVIF_CODES.all { it in dataPoints }

    // Tuya Local matches and controls devices by their data points; a device with none is camera/ONVIF-only.
    val supportsTuyaLocal: Boolean
        get() = tuyaLan && dataPoints.isNotEmpty()

    companion object {
        val TUYA_ONVIF_CODES = listOf(
            "onvif_switch", "onvif_ip_addr", "onvif_pw_changed", "onvif_change_pwd", "onvif_iptype_config"
        )
    }
}

/** One open TCP port and what answered on it. */
data class OpenPort(
    val port: Int,
    val service: String,
    val description: String,
    val detail: String?
)

enum class StreamSource { ONVIF, PORT_SCAN }

data class DetectedStream(
    val name: String,
    val url: String,
    val codec: String?,
    val resolution: String?,
    val source: StreamSource
)

data class Credentials(val username: String, val password: String) {
    override fun toString() = "Credentials(username=$username, password=[redacted])"

    companion object {
        val DEFAULT = Credentials("admin", "admin")
        val DEFAULT_PASSWORDS = listOf(
            "admin",
            "admin123",
            "123456",
            "12345678",
            "admin1234",
            "888888",
            "12345",
            "password"
        )
        val DEFAULTS = DEFAULT_PASSWORDS.map { Credentials("admin", it) } + listOf(
            Credentials("admin", ""),
            Credentials("root", "root"),
            Credentials("root", "123456")
        )

        fun candidates(preferred: Credentials? = null): List<Credentials> =
            (listOfNotNull(preferred?.takeIf { it.username.isNotEmpty() || it.password.isNotEmpty() }) + DEFAULTS).distinct()
    }
}

data class OnvifInfo(
    val serviceUrl: String,
    val manufacturer: String?,
    val model: String?,
    val firmware: String?,
    val mac: String?,
    val authRequired: Boolean,
    val streams: List<DetectedStream>,
    val credentials: Credentials? = null
)

enum class AddressSource(val label: String) {
    ONVIF_DATA_POINT("reported by the device over Tuya"),
    TUYA_LAN("Tuya LAN broadcast"),
    ONVIF_DISCOVERY("ONVIF discovery, matched by MAC")
}

data class NetworkReport(
    val ip: String,
    val addressSource: AddressSource,
    val scannedAt: Long,
    val openPorts: List<OpenPort>,
    val onvif: OnvifInfo?,
    val onvifError: String?,
    val portScanStreams: List<DetectedStream>
)
