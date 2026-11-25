package com.emi.tuyeasie.ui

import com.emi.tuyeasie.data.DeviceCapabilities
import com.emi.tuyeasie.data.HaIntegrationOption
import com.emi.tuyeasie.data.IntegrationKeys
import com.emi.tuyeasie.data.IntegrationStatus
import com.emi.tuyeasie.data.NetworkReport
import com.emi.tuyeasie.hass.HomeAssistantProtocol
import com.emi.tuyeasie.hass.TuyaLocalProbe

/**
 * The integrations this device can be added to Home Assistant as: the scan found the service, Tuyeasie has an
 * adapter for it, and Home Assistant has the config flow installed. Entries already added or failed stay listed.
 */
fun buildIntegrationCatalog(capabilities: DeviceCapabilities, report: NetworkReport?, handlers: Set<String>, previous: List<HaIntegrationOption>): List<HaIntegrationOption> {
    val oldByKey = previous.associateBy { it.key }
    val candidates = linkedMapOf<String, Pair<String, String>>()
    if (report != null) {
        if (capabilities.supportsTuyaLocal) {
            candidates[IntegrationKeys.TUYA_LOCAL] = "Tuya Local" to IntegrationKeys.TUYA_LOCAL
            candidates[IntegrationKeys.LOCAL_TUYA] = "Local Tuya" to IntegrationKeys.LOCAL_TUYA
        }
        if (report.onvif != null) candidates[IntegrationKeys.ONVIF] = "ONVIF" to "onvif"
        report.onvif?.streams.orEmpty().ifEmpty { report.portScanStreams }.indices.forEach { index ->
            candidates[haOptionKeyForStream(index)] = streamLabel(index) to "generic"
        }
    }
    val addable = candidates.filterValues { (_, handler) -> handler in handlers }
    val kept = previous.filter { it.status == IntegrationStatus.ADDED || it.status == IntegrationStatus.FAILED }.map { it.key }
    return (addable.keys + kept).distinct().map { key ->
        val old = oldByKey[key]
        val (label, handler) = addable[key] ?: checkNotNull(old) { "No previous entry for $key" }.let { it.label to it.handler }
        val status = when (old?.status) {
            IntegrationStatus.ADDED -> IntegrationStatus.ADDED
            IntegrationStatus.FAILED -> IntegrationStatus.FAILED
            else -> IntegrationStatus.AVAILABLE
        }
        HaIntegrationOption(key, label, handler, status, old?.entryId, old?.deviceRegistryId, old?.openUrl, old?.error,
            old?.updatedAt ?: System.currentTimeMillis())
    }
}

/** Which of the two Tuya integrations suits a device better, and the reason shown to the user. */
data class TuyaRecommendation(val key: String, val reason: String)

val TUYA_INTEGRATION_LABELS = mapOf(IntegrationKeys.TUYA_LOCAL to "Tuya Local", IntegrationKeys.LOCAL_TUYA to "Local Tuya")

/**
 * Tuya Local turns a device it has a profile for into proper lights, covers and climate entities, so it wins
 * whenever it matches; Local Tuya takes every other device. `probe` is null when Tuya Local is not installed,
 * and then only an IR blaster has a clear answer.
 */
fun recommendTuyaIntegration(capabilities: DeviceCapabilities, probe: TuyaLocalProbe?): TuyaRecommendation? {
    if (!capabilities.supportsTuyaLocal) return null
    if (capabilities.dataPoints.any { it.code == HomeAssistantProtocol.IR_SEND_CODE }) {
        return TuyaRecommendation(IntegrationKeys.LOCAL_TUYA, "Only Local Tuya can learn and send IR codes with it.")
    }
    return when (probe) {
        TuyaLocalProbe.MATCH -> TuyaRecommendation(IntegrationKeys.TUYA_LOCAL, "Tuya Local has a profile for this device.")
        TuyaLocalProbe.NO_MATCH -> TuyaRecommendation(IntegrationKeys.LOCAL_TUYA, "Tuya Local has no profile for this device.")
        TuyaLocalProbe.UNREACHABLE, null -> null
    }
}

private fun streamLabel(index: Int) = when (index) {
    0 -> "Camera (main stream)"
    1 -> "Camera (substream)"
    else -> "Camera (stream ${index + 1})"
}

/** The Home Assistant entry title of each added integration for a device with this name; extra streams keep their suffix. */
fun entryTitles(name: String, options: List<HaIntegrationOption>): Map<HaIntegrationOption, String> =
    options.filter { it.entryId != null }.associate { option ->
        val title = when {
            option.key == "generic:1" -> "$name Substream"
            option.key.startsWith("generic:") -> "$name Stream ${option.key.substringAfter(':').toInt() + 1}"
            else -> name
        }
        option to title
    }

fun haOptionKeyForStream(index: Int) = if (index == 0) IntegrationKeys.GENERIC else "generic:$index"
