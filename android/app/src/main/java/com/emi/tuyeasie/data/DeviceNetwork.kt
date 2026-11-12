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

data class OnvifInfo(
    val serviceUrl: String,
    val manufacturer: String?,
    val model: String?,
    val firmware: String?,
    val mac: String?,
    val authRequired: Boolean,
    val streams: List<DetectedStream>
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
