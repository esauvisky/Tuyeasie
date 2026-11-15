package com.emi.tuyeasie.ui

import com.emi.tuyeasie.data.DeviceCapabilities
import com.emi.tuyeasie.data.HaIntegrationOption
import com.emi.tuyeasie.data.IntegrationKeys
import com.emi.tuyeasie.data.IntegrationStatus
import com.emi.tuyeasie.data.NetworkReport

fun buildIntegrationCatalog(capabilities: DeviceCapabilities, report: NetworkReport?, handlers: Set<String>, previous: List<HaIntegrationOption>): List<HaIntegrationOption> {
    val oldByKey = previous.associateBy { it.key }
    val detected = linkedMapOf<String, Triple<String, String?, String?>>()
    if (capabilities.supportsTuyaLocal || oldByKey[IntegrationKeys.TUYA_LOCAL]?.status == IntegrationStatus.ADDED) {
        detected[IntegrationKeys.TUYA_LOCAL] = Triple("Tuya Local", "tuya_local", null)
    }
    if ((report?.onvif != null || report?.openPorts?.any { it.service == "ONVIF" } == true) || oldByKey[IntegrationKeys.ONVIF]?.status == IntegrationStatus.ADDED) {
        detected[IntegrationKeys.ONVIF] = Triple("ONVIF", "onvif", null)
    }
    val streams = report?.onvif?.streams.orEmpty().ifEmpty { report?.portScanStreams.orEmpty() }
    streams.forEachIndexed { index, stream ->
        val key = haOptionKeyForStream(index)
        val label = if (index == 0) "Generic Camera" else "Generic Camera · ${stream.name}"
        detected[key] = Triple(label, "generic", null)
    }
    if (streams.isEmpty() && report?.openPorts?.any { it.service == "RTSP" } == true) {
        detected[IntegrationKeys.GENERIC] = Triple("Generic Camera", "generic", "RTSP detected but no verified stream URI was found.")
    }
    report?.openPorts?.forEach { port ->
        val key = "service:${port.port}:${port.service}"
        detected.putIfAbsent(key, Triple("${port.service} · ${port.port}", null, "Detected service; Tuyeasie has no compatible HA setup adapter."))
    }
    oldByKey.values.filter { it.status == IntegrationStatus.ADDED || it.status == IntegrationStatus.FAILED }.forEach { old ->
        detected.putIfAbsent(old.key, Triple(old.label, old.handler, null))
    }
    return detected.map { (key, evidence) ->
        val (label, handler, evidenceReason) = evidence
        val old = oldByKey[key]
        val known = key == IntegrationKeys.TUYA_LOCAL || key == IntegrationKeys.ONVIF || key == IntegrationKeys.GENERIC || key.startsWith("generic:")
        val flowInstalled = handler != null && handler in handlers
        val enabled = old?.status == IntegrationStatus.ADDED || old?.status == IntegrationStatus.FAILED || (evidenceReason == null && known && flowInstalled && report != null)
        val reason = when {
            old?.status == IntegrationStatus.ADDED || old?.status == IntegrationStatus.FAILED -> null
            evidenceReason != null -> evidenceReason
            report == null -> "A local address is required before setup."
            !known -> "Detected, but Tuyeasie has no setup adapter for this protocol."
            !flowInstalled -> "Home Assistant does not report the '$handler' configuration flow."
            else -> null
        }
        val status = when {
            old?.status == IntegrationStatus.ADDED -> IntegrationStatus.ADDED
            old?.status == IntegrationStatus.FAILED && enabled -> IntegrationStatus.FAILED
            enabled -> IntegrationStatus.AVAILABLE
            else -> IntegrationStatus.REMOVED
        }
        HaIntegrationOption(key, label, handler, key.takeIf { it.startsWith("service:") }, enabled, reason, status,
            old?.entryId, old?.deviceRegistryId, old?.openUrl, old?.error, old?.updatedAt ?: System.currentTimeMillis())
    }
}

fun supportsDynamicField(field: com.emi.tuyeasie.data.HaFlowField): Boolean =
    field.type in setOf("string", "password", "boolean", "integer", "number", "select") &&
        (field.type != "select" || field.options.isNotEmpty())

fun haOptionKeyForStream(index: Int) = if (index == 0) IntegrationKeys.GENERIC else "generic:$index"
