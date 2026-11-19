package com.emi.tuyeasie.data

/** A Tuya DP schema item. The raw property JSON is retained so controls can be derived without lossy parsing. */
data class TuyaDataPointSchema(
    val id: String,
    val code: String,
    val name: String,
    val mode: String,
    val type: String,
    val propertyJson: String,
    val schemaType: String
) {
    // "wr" is write-only: the device accepts it but never reports it.
    val writable: Boolean
        get() = mode == "rw" || mode == "wr"

    // `type` is the transport kind ("obj", "raw", "file"); the value kind of an "obj" DP is in `schemaType`.
    val editorType: String?
        get() {
            if (!writable) return null
            if (type == "raw") return "raw"
            if (type != "obj") return null
            return when (schemaType) {
                "bool" -> "boolean"
                "enum" -> "enum"
                "value" -> "integer"
                "bitmap" -> "bitmap"
                "string" -> "string"
                else -> null
            }
        }
}

data class DeviceCapabilities(
    val dataPoints: List<TuyaDataPointSchema>,
    val mac: String?,
    val tuyaLan: Boolean
) {
    val tuyaOnvif: Boolean
        get() = TUYA_ONVIF_CODES.all { code -> dataPoints.any { it.code == code } }
    // Some cameras have only the switch, without the password and IP data points `tuyaOnvif` needs.
    val onvifSwitch: TuyaDataPointSchema?
        get() = dataPoints.find { it.code == "onvif_switch" }
    val supportsTuyaLocal: Boolean
        get() = tuyaLan && dataPoints.isNotEmpty()

    fun dataPointById(id: String): TuyaDataPointSchema = dataPoints.single { it.id == id }
    fun dataPointByCode(code: String): TuyaDataPointSchema = dataPoints.single { it.code == code }

    companion object {
        val TUYA_ONVIF_CODES = listOf(
            "onvif_switch", "onvif_ip_addr", "onvif_pw_changed", "onvif_change_pwd", "onvif_iptype_config"
        )
    }
}
enum class IntegrationStatus { AVAILABLE, ADDING, ADDED, FAILED }

object IntegrationKeys {
    const val TUYA_LOCAL = "tuya_local"
    const val ONVIF = "onvif"
    const val GENERIC = "generic"
}

data class HaIntegrationOption(
    val key: String,
    val label: String,
    val handler: String?,
    val status: IntegrationStatus,
    val entryId: String?,
    val deviceRegistryId: String?,
    val openUrl: String?,
    val error: String?,
    val updatedAt: Long
)

data class HaEntryAssociation(
    val entryId: String,
    val deviceRegistryId: String?,
    val openUrl: String
)

data class ManualDataPoint(
    val id: String,
    val value: Any?,
    val reading: Boolean = false,
    val writing: Boolean = false,
    val error: String? = null
)

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
        val DEFAULT_PASSWORDS = listOf("admin", "admin123", "123456", "12345678", "admin1234", "888888", "12345", "password")
        val DEFAULTS = DEFAULT_PASSWORDS.map { Credentials("admin", it) } + listOf(
            Credentials("admin", ""), Credentials("root", "root"), Credentials("root", "123456")
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
    ONVIF_DATA_POINT("Reported by the camera"),
    TUYA_LAN("Tuya LAN discovery"),
    ONVIF_DISCOVERY("ONVIF discovery")
}

data class NetworkReport(
    val ip: String?,
    val addressSource: AddressSource?,
    val scannedAt: Long,
    val openPorts: List<OpenPort>,
    val onvif: OnvifInfo?,
    val onvifError: String?,
    val portScanStreams: List<DetectedStream>
)
