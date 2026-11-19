package com.emi.tuyeasie.hass

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONException
import com.alibaba.fastjson.JSONObject
import com.emi.tuyeasie.data.ActivatedDevice
import com.emi.tuyeasie.data.Credentials
import com.emi.tuyeasie.data.HaEntryAssociation
import com.emi.tuyeasie.data.HaIntegrationOption
import com.emi.tuyeasie.data.NetworkReport
import com.emi.tuyeasie.data.SecureSettingsStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

enum class ImportOutcome {
    ADDED,
    UPDATED,
    ALREADY_PRESENT
}

data class ImportResult(
    val outcome: ImportOutcome,
    val entryId: String,
    val loaded: Boolean
)

enum class HomeAssistantError {
    UNKNOWN_COMMAND,
    UNAUTHORIZED,
    INVALID_FORMAT,
    HANDSHAKE_FAILED,
    UNSUPPORTED_PROFILE,
    UNSUPPORTED_PROTOCOL,
    DISCOVERY_FAILED,
    NON_ADMIN,
    CONNECTION_FAILED,
    INVALID_RESPONSE,
    ENTRY_NOT_LOADED,
    TIMEOUT,
    INTERRUPTED,
    SERVER_ERROR
}

class HomeAssistantException(
    val error: HomeAssistantError,
    val publicMessage: String,
    cause: Throwable? = null
) : Exception(publicMessage, cause)

class HomeAssistantClient(
    private val settingsStore: SecureSettingsStore,
    private val httpClient: OkHttpClient = DEFAULT_HTTP_CLIENT
) {

    fun availableFlowHandlers(): Set<String> {
        val response = httpRequest("GET", "/api/config/config_entries/flow_handlers", null)
        val handlers = try {
            JSON.parseArray(response)
        } catch (e: JSONException) {
            throw HomeAssistantException(HomeAssistantError.INVALID_RESPONSE, "Home Assistant returned an invalid flow handler list.", e)
        } ?: throw invalidResponse("Home Assistant returned no flow handler list.")
        return (0 until handlers.size).map { index -> handlers.getString(index) }.toSet()
    }

    fun entryAssociation(entryId: String, domain: String): HaEntryAssociation =
        openVerifiedSession().use { session -> association(deviceRegistry(session), entryId, domain) }

    /** The current device link of every entry (ID to domain) that still exists; entries deleted in Home Assistant are absent. */
    fun entryAssociations(domains: Map<String, String>): Map<String, HaEntryAssociation> = withVerifiedSession { session ->
        val entries = session.callCommand("config_entries/get").getJSONArray("result")
            ?: throw invalidResponse("Home Assistant returned no config-entry list.")
        val existing = (0 until entries.size).map { entries.getJSONObject(it).getString("entry_id") }.toSet()
        val registry = deviceRegistry(session)
        domains.filterKeys { it in existing }.mapValues { (entryId, domain) -> association(registry, entryId, domain) }
    }

    private fun deviceRegistry(session: WebSocketSession): JSONArray =
        session.callCommand("config/device_registry/list").getJSONArray("result")
            ?: throw invalidResponse("Home Assistant returned no device registry.")

    private fun association(registry: JSONArray, entryId: String, domain: String): HaEntryAssociation {
        val device = (0 until registry.size)
            .map { registry.getJSONObject(it) }
            .firstOrNull { candidate ->
                val configEntries = candidate.getJSONArray("config_entries")
                    ?: throw invalidResponse("A Home Assistant device has no config entries.")
                (0 until configEntries.size).any { configEntries.getString(it) == entryId }
            }
        val registryId = device?.getString("id")
        val base = settingsStore.loadSettings().hassUrl.trimEnd('/')
        val openUrl = if (registryId != null) "$base/config/devices/device/$registryId"
        else "$base/config/integrations/integration/$domain#config_entry=$entryId"
        return HaEntryAssociation(entryId, registryId, openUrl)
    }

    fun addIntegration(option: HaIntegrationOption, device: ActivatedDevice, report: NetworkReport, credentials: Credentials?): HaEntryAssociation {
        val entryId = when (option.key) {
            "tuya_local" -> importDevice(
                device.copy(ip = checkNotNull(report.ip) { "Tuya Local needs a LAN address" }),
                report.onvif?.streams?.firstOrNull()?.url,
                report.onvif?.streams?.getOrNull(1)?.url
            ).entryId.also { entryId ->
                // Tuya Local ships its camera entities disabled.
                openVerifiedSession().use { session -> enableCameraEntities(session, setOf(entryId)) }
            }
            "onvif" -> {
                val service = checkNotNull(report.onvif) { "No ONVIF service probe result is available" }
                val uri = java.net.URI(service.serviceUrl)
                createOnvifEntry(device.name, uri.host, if (uri.port == -1) 80 else uri.port,
                    credentials?.username ?: "admin", credentials?.password ?: "admin")
            }
            "generic" -> {
                val streams = report.onvif?.streams.orEmpty().ifEmpty { report.portScanStreams }
                check(streams.isNotEmpty()) { "No verified RTSP stream was detected" }
                val used = credentials ?: report.onvif?.credentials ?: Credentials.DEFAULT
                createCamera(device.name, streams.first().url, used.username, used.password)
            }
            else -> if (option.key.startsWith("generic:")) {
                val streams = report.onvif?.streams.orEmpty().ifEmpty { report.portScanStreams }
                val index = option.key.substringAfter(':').toInt()
                val stream = checkNotNull(streams.getOrNull(index)) { "No verified RTSP stream at index $index" }
                val used = credentials ?: report.onvif?.credentials ?: Credentials.DEFAULT
                // Stream names are firmware profile tokens such as PROFILE_001, so the entry is named by position.
                val title = if (index == 1) "${device.name} Substream" else "${device.name} Stream ${index + 1}"
                createCamera(title, stream.url, used.username, used.password)
            } else throw HomeAssistantException(HomeAssistantError.INVALID_FORMAT, "No explicit setup adapter for ${option.key}")
        }
        return entryAssociation(entryId, checkNotNull(option.handler))
    }

    fun testConnection(): Boolean {
        openSession().use { session ->
            authenticate(session)
            requireAdmin(session)
            return true
        }
    }

    fun importDevice(
        device: ActivatedDevice,
        mainStreamUrl: String? = null,
        subStreamUrl: String? = null
    ): ImportResult {
        validateDevice(device)
        val reconnectDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(RECONNECT_WINDOW_SECONDS)
        while (true) {
            val session = try {
                openVerifiedSession()
            } catch (exception: HomeAssistantException) {
                if (exception.error != HomeAssistantError.CONNECTION_FAILED ||
                    System.nanoTime() >= reconnectDeadline
                ) {
                    throw exception
                }
                try {
                    Thread.sleep(RECONNECT_DELAY_MILLIS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw HomeAssistantException(
                        HomeAssistantError.INTERRUPTED,
                        "Home Assistant reconnection was interrupted."
                    )
                }
                continue
            }
            session.use {
                return try {
                    val outcome = sendImport(session, device, mainStreamUrl, subStreamUrl)
                    awaitLoadedEntry(session, device, outcome)
                } catch (exception: HomeAssistantException) {
                    if (exception.error != HomeAssistantError.UNKNOWN_COMMAND) throw exception
                    // Stock tuya-local does not register tuya_local/import_device; drive its standard REST config flow.
                    importViaConfigFlow(session, device)
                }
            }
        }
    }

    private fun importViaConfigFlow(session: WebSocketSession, device: ActivatedDevice): ImportResult {
        val host = device.ip?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw HomeAssistantException(
                HomeAssistantError.DISCOVERY_FAILED,
                "Could not find the device on the local network. Check that it is powered on and on the same Wi-Fi."
            )
        val protocolVersion = device.protocolVersion?.trim()
            ?.takeIf { HomeAssistantProtocol.isSupportedProtocolVersion(it) }
            ?: "auto"
        val existingId = findTuyaLocalEntryId(session, device.deviceId)
        if (existingId != null) {
            updateTuyaLocalOptions(existingId, host, device.localKey, protocolVersion)
            session.callCommand("config_entries/update", mapOf("entry_id" to existingId, "title" to device.name))
            return awaitLoadedEntry(session, device, ImportOutcome.UPDATED)
        }
        var step = postFlow("/api/config/config_entries/flow", mapOf("handler" to "tuya_local"))
        val flowId = step.getString("flow_id")
            ?: throw invalidResponse("Home Assistant returned no Tuya Local flow ID.")
        val flowPath = "/api/config/config_entries/flow/$flowId"
        if (step.getString("type") == "form" && step.getString("step_id") == "user") {
            step = postFlow(flowPath, mapOf("setup_mode" to "manual"))
        }
        requireFlowStep(step, "local")
        step = postFlow(
            flowPath,
            mapOf(
                "device_id" to device.deviceId,
                "host" to host,
                "local_key" to device.localKey,
                "protocol_version" to protocolVersion,
                "poll_only" to false
            )
        )
        if (step.getString("type") == "abort") {
            if (step.getString("reason") == "not_supported") {
                throw HomeAssistantException(
                    HomeAssistantError.UNSUPPORTED_PROFILE,
                    "The device profile is not supported by Tuya Local."
                )
            }
            throw flowFailure(step)
        }
        val stepId = step.getString("step_id")
        if (step.getString("type") != "form" ||
            (stepId != "select_type" && stepId != "select_type_auto_detected")
        ) {
            throw flowFailure(step)
        }
        val selectedType = extractDefaultType(step)
            ?: throw HomeAssistantException(
                HomeAssistantError.UNSUPPORTED_PROFILE,
                "Tuya Local offered no matching device profile."
            )
        step = postFlow(flowPath, mapOf("type" to selectedType))
        requireFlowStep(step, "choose_entities")
        val choosePayload = linkedMapOf<String, Any?>("name" to device.name)
        val schema = step.getJSONArray("data_schema")
        if (schema != null) {
            for (index in 0 until schema.size) {
                val field = schema.getJSONObject(index) ?: continue
                val fieldName = field.getString("name") ?: continue
                if (fieldName != "name" && field.containsKey("default")) {
                    choosePayload[fieldName] = field.get("default")
                }
            }
        }
        val created = postFlow(flowPath, choosePayload)
        if (created.getString("type") != "create_entry") {
            throw flowFailure(created)
        }
        return awaitLoadedEntry(session, device, ImportOutcome.ADDED)
    }

    // Entry summaries from config_entries/get carry no unique_id, so match on the device registry identifier
    // tuya-local registers: ("tuya_local", <device id>), then keep the device's tuya_local entry.
    private fun findTuyaLocalEntryId(session: WebSocketSession, deviceId: String): String? {
        val entries = session.callCommand("config_entries/get").getJSONArray("result")
            ?: throw invalidResponse("Home Assistant returned no config-entry list.")
        val tuyaLocalEntries = (0 until entries.size)
            .map { entries.getJSONObject(it) }
            .filter { it.getString("domain") == "tuya_local" }
            .map { checkNotNull(it.getString("entry_id")) { "A tuya_local config entry has no entry ID" } }
            .toSet()
        val devices = session.callCommand("config/device_registry/list").getJSONArray("result")
            ?: throw invalidResponse("Home Assistant returned no device registry.")
        for (index in 0 until devices.size) {
            val device = devices.getJSONObject(index)
            val identifiers = device.getJSONArray("identifiers") ?: continue
            val matches = (0 until identifiers.size).any { i ->
                val pair = identifiers.getJSONArray(i)
                pair.getString(0) == "tuya_local" && pair.getString(1) == deviceId
            }
            if (!matches) continue
            val deviceEntries = device.getJSONArray("config_entries")
                ?: throw invalidResponse("A Home Assistant device has no config entries.")
            return (0 until deviceEntries.size).map { deviceEntries.getString(it) }.single { it in tuyaLocalEntries }
        }
        return null
    }

    private fun updateTuyaLocalOptions(
        entryId: String,
        host: String,
        localKey: String,
        protocolVersion: String
    ) {
        val start = postFlow("/api/config/config_entries/options/flow", mapOf("handler" to entryId))
        requireFlowStep(start, "user")
        val flowId = start.getString("flow_id")
            ?: throw invalidResponse("Home Assistant returned no Tuya Local options flow ID.")
        val result = postFlow(
            "/api/config/config_entries/options/flow/$flowId",
            mapOf(
                "local_key" to localKey,
                "host" to host,
                "protocol_version" to protocolVersion,
                "poll_only" to false
            )
        )
        if (result.getString("type") != "create_entry") {
            throw flowFailure(result)
        }
    }

    private fun extractDefaultType(step: JSONObject): String? {
        val schema = step.getJSONArray("data_schema") ?: return null
        for (index in 0 until schema.size) {
            val field = schema.getJSONObject(index) ?: continue
            if (field.getString("name") != "type") continue
            field.getString("default")?.takeIf { it.isNotBlank() }?.let { return it }
            val options = field.getJSONObject("selector")
                ?.getJSONObject("select")
                ?.getJSONArray("options")
            if (options != null && options.isNotEmpty()) {
                val first = options.get(0)
                if (first is JSONObject) {
                    return first.getString("value")
                }
                if (first is String) {
                    return first
                }
            }
        }
        return null
    }

    /** Removes every tracked integration entry, entries linked in HA's device registry, and legacy titled entries. */
    fun removeDevice(deviceId: String, entryIds: List<String>, legacyCameraTitles: List<String>): Int {
        val targets = withVerifiedSession { session ->
            val entries = session.callCommand("config_entries/get").getJSONArray("result")
                ?: throw invalidResponse("Home Assistant returned no config-entry list.")
            val registry = session.callCommand("config/device_registry/list").getJSONArray("result")
                ?: throw invalidResponse("Home Assistant returned no device registry.")
            val linkedIds = mutableListOf<String>()
            for (index in 0 until registry.size) {
                val device = registry.getJSONObject(index)
                val identifiers = device.getJSONArray("identifiers") ?: continue
                val linked = (0 until identifiers.size).any { identifierIndex ->
                    val identifier = identifiers.getJSONArray(identifierIndex)
                    identifier.size >= 2 && identifier.getString(1) == deviceId
                }
                if (linked) {
                    val configEntries = device.getJSONArray("config_entries")
                        ?: throw invalidResponse("A Home Assistant device has no config_entries list.")
                    for (entryIndex in 0 until configEntries.size) linkedIds += configEntries.getString(entryIndex)
                }
            }
            val legacyIds = mutableListOf<String>()
            val existingIds = mutableSetOf<String>()
            for (index in 0 until entries.size) {
                val entry = entries.getJSONObject(index)
                val id = checkNotNull(entry.getString("entry_id")) { "A config entry has no entry ID" }
                existingIds += id
                if (entry.getString("domain") == "generic" && entry.getString("title") in legacyCameraTitles) legacyIds += id
            }
            (entryIds + linkedIds + legacyIds).distinct().filter { it in existingIds }
        }
        targets.forEach(::removeIntegrationEntry)
        return targets.size
    }

    /** Sets each config entry's title, and the same name on every Home Assistant device that entry created. */
    fun renameEntries(titles: Map<String, String>) = withVerifiedSession { session ->
        val registry = session.callCommand("config/device_registry/list").getJSONArray("result")
            ?: throw invalidResponse("Home Assistant returned no device registry.")
        titles.forEach { (entryId, title) ->
            session.callCommand("config_entries/update", mapOf("entry_id" to entryId, "title" to title))
            for (index in 0 until registry.size) {
                val device = registry.getJSONObject(index)
                val configEntries = device.getJSONArray("config_entries")
                    ?: throw invalidResponse("A Home Assistant device has no config_entries list.")
                if ((0 until configEntries.size).none { configEntries.getString(it) == entryId }) continue
                session.callCommand("config/device_registry/update", mapOf("device_id" to device.getString("id"), "name_by_user" to title))
            }
        }
    }

    /** Swaps the old name's slug for the new one at the start of every entity ID these entries own. */
    fun renameEntityIds(entryIds: Set<String>, oldName: String, newName: String) {
        val oldSlug = HomeAssistantProtocol.entitySlug(oldName)
        val newSlug = HomeAssistantProtocol.entitySlug(newName)
        if (newSlug.isEmpty()) {
            throw HomeAssistantException(HomeAssistantError.INVALID_FORMAT, "\"$newName\" has no letters or digits an entity ID can use.")
        }
        withVerifiedSession { session ->
            val registry = session.callCommand("config/entity_registry/list").getJSONArray("result")
                ?: throw invalidResponse("Home Assistant returned no entity registry.")
            val entities = (0 until registry.size).map { registry.getJSONObject(it) }
            val taken = entities
                .map { checkNotNull(it.getString("entity_id")) { "An entity registry entry has no entity_id" } }
                .toMutableSet()
            for (entity in entities) {
                val entryId = entity.getString("config_entry_id") ?: continue
                if (entryId !in entryIds) continue
                val entityId = entity.getString("entity_id")
                val domain = entityId.substringBefore('.')
                val objectId = entityId.substringAfter('.')
                // IDs that were not built from the old name are left alone.
                if (objectId != oldSlug && !objectId.startsWith("${oldSlug}_")) continue
                val wanted = HomeAssistantProtocol.freeEntityId("$domain.$newSlug${objectId.removePrefix(oldSlug)}", taken - entityId)
                if (wanted == entityId) continue
                session.callCommand("config/entity_registry/update", mapOf("entity_id" to entityId, "new_entity_id" to wanted))
                taken -= entityId
                taken += wanted
            }
        }
    }

    private fun <T> withVerifiedSession(block: (WebSocketSession) -> T): T {
        val reconnectDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(RECONNECT_WINDOW_SECONDS)
        while (true) {
            val session = try {
                openVerifiedSession()
            } catch (exception: HomeAssistantException) {
                if (exception.error != HomeAssistantError.CONNECTION_FAILED ||
                    System.nanoTime() >= reconnectDeadline
                ) {
                    throw exception
                }
                try {
                    Thread.sleep(RECONNECT_DELAY_MILLIS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw HomeAssistantException(
                        HomeAssistantError.INTERRUPTED,
                        "Home Assistant reconnection was interrupted."
                    )
                }
                continue
            }
            return session.use(block)
        }
    }

    fun removeIntegrationEntry(entryId: String) {
        try {
            httpRequest("DELETE", "/api/config/config_entries/entry/$entryId", null)
        } catch (error: HomeAssistantException) {
            val stillPresent = withVerifiedSession { session ->
                val entries = session.callCommand("config_entries/get").getJSONArray("result")
                    ?: throw invalidResponse("Home Assistant returned no config-entry list.")
                (0 until entries.size).any { entries.getJSONObject(it).getString("entry_id") == entryId }
            }
            if (stillPresent) throw error
        }
    }

    private fun enableCameraEntities(session: WebSocketSession, entryIds: Set<String>) {
        val registry = session.callCommand("config/entity_registry/list").getJSONArray("result")
            ?: throw invalidResponse("Home Assistant returned no entity registry.")
        val touchedEntries = mutableSetOf<String>()
        for (index in 0 until registry.size) {
            val entity = registry.getJSONObject(index)
            val configEntryId = entity.getString("config_entry_id") ?: continue
            if (configEntryId !in entryIds) continue
            val disabledBy = entity.getString("disabled_by") ?: continue
            val entityId = checkNotNull(entity.getString("entity_id")) { "An entity registry entry has no entity_id" }
            if (entityId.startsWith("camera.") && disabledBy == "integration") {
                session.callCommand(
                    "config/entity_registry/update",
                    mapOf("entity_id" to entityId, "disabled_by" to null)
                )
                touchedEntries += configEntryId
            }
        }
        touchedEntries.forEach { awaitEntryLoaded(session, it) }
    }

    fun createCamera(name: String, streamUrl: String, username: String, password: String): String {
        if (name.isBlank() || streamUrl.isBlank()) {
            throw HomeAssistantException(
                HomeAssistantError.INVALID_FORMAT,
                "Camera name and stream URL are required."
            )
        }
        val entryId = findCameraEntryId(name) ?: createCameraEntry(streamUrl, username, password)
        openVerifiedSession().use { session ->
            session.callCommand("config_entries/update", mapOf("entry_id" to entryId, "title" to name))
            awaitEntryLoaded(session, entryId)
            enableCameraEntities(session, setOf(entryId))
            nameCameraEntity(session, entryId, name)
        }
        return entryId
    }

    // Home Assistant builds a generic camera's entity ID from its stream's host; it is replaced with the camera's name.
    private fun nameCameraEntity(session: WebSocketSession, entryId: String, name: String) {
        val slug = HomeAssistantProtocol.entitySlug(name)
        // A name with no Latin letters or digits has no slug, so Home Assistant's own ID stays.
        if (slug.isEmpty()) return
        val registry = session.callCommand("config/entity_registry/list").getJSONArray("result")
            ?: throw invalidResponse("Home Assistant returned no entity registry.")
        val entities = (0 until registry.size).map { registry.getJSONObject(it) }
        val ids = entities.map { checkNotNull(it.getString("entity_id")) { "An entity registry entry has no entity_id" } }
        val current = entities
            .filter { it.getString("config_entry_id") == entryId && it.getString("entity_id").startsWith("camera.") }
            .singleOrNull()?.getString("entity_id")
            ?: throw invalidResponse("The generic camera entry does not have exactly one camera entity.")
        val wanted = HomeAssistantProtocol.freeEntityId("camera.$slug", ids.toSet() - current)
        if (wanted == current) return
        session.callCommand("config/entity_registry/update", mapOf("entity_id" to current, "new_entity_id" to wanted))
    }

    private fun findCameraEntryId(name: String): String? {
        val entries = try {
            JSON.parseArray(httpRequest("GET", "/api/config/config_entries/entry?domain=generic", null))
        } catch (e: JSONException) {
            throw HomeAssistantException(
                HomeAssistantError.INVALID_RESPONSE,
                "Home Assistant returned invalid JSON.",
                e
            )
        } ?: throw invalidResponse("Home Assistant returned no generic camera entry list.")
        for (index in 0 until entries.size) {
            val entry = entries.getJSONObject(index)
            if (entry.getString("title") == name) {
                return entry.getString("entry_id")
                    ?: throw invalidResponse("A generic camera entry has no entry ID.")
            }
        }
        return null
    }

    private fun createCameraEntry(streamUrl: String, username: String, password: String): String {
        val start = postFlow("/api/config/config_entries/flow", mapOf("handler" to "generic"))
        requireFlowStep(start, "user")
        val flowId = start.getString("flow_id")
            ?: throw invalidResponse("Home Assistant returned no camera flow ID.")
        val flowPath = "/api/config/config_entries/flow/$flowId"
        val form = postFlow(
            flowPath,
            mapOf(
                "stream_source" to streamUrl,
                "username" to username,
                "password" to password,
                "advanced" to mapOf(
                    "framerate" to 2,
                    "verify_ssl" to true,
                    "rtsp_transport" to "tcp",
                    "authentication" to "basic"
                )
            )
        )
        requireFlowStep(form, "user_confirm")
        val created = postFlow(flowPath, mapOf("confirmed_ok" to true))
        if (created.getString("type") != "create_entry") {
            throw flowFailure(created)
        }
        return created.getJSONObject("result")?.getString("entry_id")
            ?: throw invalidResponse("Home Assistant returned no camera entry ID.")
    }

    fun createOnvifEntry(name: String, host: String, port: Int, username: String, password: String): String {
        if (name.isBlank() || host.isBlank()) {
            throw HomeAssistantException(
                HomeAssistantError.INVALID_FORMAT,
                "ONVIF camera name and host are required."
            )
        }
        // Always run the flow: for a device HA already knows (same MAC), it writes the new address and credentials
        // onto the existing entry and aborts with already_configured.
        val entryId = createOnvifFlowEntry(name, host, port, username, password)
        openVerifiedSession().use { session ->
            session.callCommand("config_entries/update", mapOf("entry_id" to entryId, "title" to name))
            awaitEntryLoaded(session, entryId)
            enableCameraEntities(session, setOf(entryId))
        }
        return entryId
    }

    // Entry data is not exposed over the API, but the ONVIF reconfigure form suggests the entry's current host
    // and port, so each entry's reconfigure flow is opened, read and deleted without changing anything.
    private fun findOnvifEntryId(host: String, port: Int): String? {
        val entries = try {
            JSON.parseArray(httpRequest("GET", "/api/config/config_entries/entry?domain=onvif", null))
        } catch (e: JSONException) {
            throw HomeAssistantException(
                HomeAssistantError.INVALID_RESPONSE,
                "Home Assistant returned invalid JSON.",
                e
            )
        } ?: throw invalidResponse("Home Assistant returned no ONVIF entry list.")
        for (index in 0 until entries.size) {
            val entryId = entries.getJSONObject(index).getString("entry_id")
                ?: throw invalidResponse("An ONVIF entry has no entry ID.")
            val form = postFlow("/api/config/config_entries/flow", mapOf("handler" to "onvif", "entry_id" to entryId))
            val flowId = form.getString("flow_id")
                ?: throw invalidResponse("Home Assistant returned no ONVIF reconfigure flow ID.")
            val address = try {
                requireFlowStep(form, "reconfigure")
                HomeAssistantProtocol.onvifReconfigureAddress(form)
            } finally {
                httpRequest("DELETE", "/api/config/config_entries/flow/$flowId", null)
            }
            if (address == host to port) return entryId
        }
        return null
    }

    private fun createOnvifFlowEntry(name: String, host: String, port: Int, username: String, password: String): String {
        val start = postFlow("/api/config/config_entries/flow", mapOf("handler" to "onvif"))
        requireFlowStep(start, "user")
        val flowId = start.getString("flow_id")
            ?: throw invalidResponse("Home Assistant returned no ONVIF flow ID.")
        val flowPath = "/api/config/config_entries/flow/$flowId"
        val form = postFlow(flowPath, mapOf("auto" to false))
        requireFlowStep(form, "configure")
        val result = postFlow(flowPath, HomeAssistantProtocol.onvifConfigurePayload(name, host, port, username, password))
        if (result.getString("type") == "create_entry") {
            return result.getJSONObject("result")?.getString("entry_id")
                ?: throw invalidResponse("Home Assistant returned no ONVIF entry ID.")
        }
        // The device's MAC or serial already has an entry; the flow moved that entry to this host and port.
        if (result.getString("type") == "abort" && result.getString("reason") == "already_configured") {
            return findOnvifEntryId(host, port)
                ?: throw invalidResponse("Home Assistant reported the ONVIF device as configured, but no entry uses $host:$port.")
        }
        throw HomeAssistantProtocol.onvifFlowFailure(result)
    }

    private fun requireFlowStep(response: JSONObject, stepId: String) {
        if (response.getString("type") != "form" || response.getString("step_id") != stepId) {
            throw flowFailure(response)
        }
        val errors = response.getJSONObject("errors")
        if (errors != null && errors.isNotEmpty()) {
            throw flowFailure(response)
        }
    }

    private fun flowFailure(response: JSONObject): HomeAssistantException {
        val detail = response.getJSONObject("errors")?.toJSONString()
            ?: response.getString("reason")
            ?: response.getString("type")
        return HomeAssistantException(
            HomeAssistantError.SERVER_ERROR,
            "Home Assistant rejected the camera configuration: $detail"
        )
    }

    private fun postFlow(path: String, body: Map<String, Any?>): JSONObject {
        val text = httpRequest("POST", path, JSON.toJSONString(body))
        return try {
            JSON.parseObject(text)
        } catch (e: JSONException) {
            throw HomeAssistantException(
                HomeAssistantError.INVALID_RESPONSE,
                "Home Assistant returned invalid JSON.",
                e
            )
        } ?: throw invalidResponse("Home Assistant returned an empty response.")
    }

    private fun httpRequest(method: String, path: String, jsonBody: String?): String {
        val settings = settingsStore.loadSettings()
        if (settings.hassUrl.isBlank() || settings.adminToken.isBlank()) {
            throw HomeAssistantException(
                HomeAssistantError.INVALID_FORMAT,
                "Home Assistant URL and admin token are required."
            )
        }
        val base = settings.hassUrl.trim().trimEnd('/')
        if (!base.startsWith("http://", ignoreCase = true) && !base.startsWith("https://", ignoreCase = true)) {
            throw HomeAssistantException(
                HomeAssistantError.INVALID_FORMAT,
                "Home Assistant URL must use http or https."
            )
        }
        val builder = try {
            Request.Builder().url(base + path)
        } catch (e: IllegalArgumentException) {
            throw HomeAssistantException(
                HomeAssistantError.INVALID_FORMAT,
                "Home Assistant URL is invalid."
            )
        }
        builder.header("Authorization", "Bearer ${settings.adminToken}")
        builder.method(method, jsonBody?.toRequestBody("application/json".toMediaType()))
        try {
            httpClient.newCall(builder.build()).execute().use { response ->
                if (response.code == 401) throw serverError("unauthorized")
                if (response.code != 200 && response.code != 201) {
                    throw HomeAssistantException(
                        HomeAssistantError.SERVER_ERROR,
                        "Home Assistant returned HTTP ${response.code} for $method $path."
                    )
                }
                return response.body?.string()
                    ?: throw invalidResponse("Home Assistant returned an empty response.")
            }
        } catch (e: IOException) {
            throw HomeAssistantException(
                HomeAssistantError.CONNECTION_FAILED,
                "Could not reach the Home Assistant REST API (${e.javaClass.simpleName}: ${e.message}).",
                e
            )
        }
    }

    private fun awaitEntryLoaded(session: WebSocketSession, entryId: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(IMPORT_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            val response = session.callCommand("config_entries/get")
            val entries = response.getJSONArray("result")
                ?: throw invalidResponse("Home Assistant returned no config-entry list.")
            for (index in 0 until entries.size) {
                val entry = entries.getJSONObject(index)
                if (entry.getString("entry_id") == entryId && entry.getString("state") == "loaded") {
                    return
                }
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw HomeAssistantException(
                    HomeAssistantError.INTERRUPTED,
                    "Config-entry loading poll was interrupted."
                )
            }
        }
        throw HomeAssistantException(
            HomeAssistantError.ENTRY_NOT_LOADED,
            "Timed out waiting for the camera config entry to finish loading."
        )
    }

    private fun openVerifiedSession(): WebSocketSession {
        val session = openSession()
        try {
            authenticate(session)
            requireAdmin(session)
            return session
        } catch (exception: Exception) {
            session.close()
            throw exception
        }
    }

    private fun openSession(): WebSocketSession {
        val settings = settingsStore.loadSettings()
        if (settings.hassUrl.isBlank() || settings.adminToken.isBlank()) {
            throw HomeAssistantException(
                HomeAssistantError.INVALID_FORMAT,
                "Home Assistant URL and admin token are required."
            )
        }
        val url = HomeAssistantProtocol.webSocketUrl(settings.hassUrl)
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            throw HomeAssistantException(
                HomeAssistantError.INVALID_FORMAT,
                "Home Assistant URL is invalid."
            )
        }
        return WebSocketSession(httpClient, request, settings.adminToken)
    }

    private fun authenticate(session: WebSocketSession) {
        val required = session.nextJson()
        if (required.getString("type") != "auth_required") {
            throw HomeAssistantException(
                HomeAssistantError.HANDSHAKE_FAILED,
                "Home Assistant did not request WebSocket authentication."
            )
        }
        session.send(
            mapOf(
                "type" to "auth",
                "access_token" to session.adminToken
            )
        )
        val response = session.nextJson()
        when (response.getString("type")) {
            "auth_ok" -> Unit
            "auth_invalid" -> throw HomeAssistantException(
                HomeAssistantError.UNAUTHORIZED,
                "Home Assistant rejected the admin access token."
            )
            else -> throw HomeAssistantException(
                HomeAssistantError.HANDSHAKE_FAILED,
                "Home Assistant WebSocket authentication failed."
            )
        }
    }

    private fun requireAdmin(session: WebSocketSession) {
        val response = session.callCommand("auth/current_user")
        val user = response.getJSONObject("result")
            ?: throw invalidResponse("Home Assistant returned no current-user record.")
        if (!user.getBooleanValue("is_admin")) {
            throw HomeAssistantException(
                HomeAssistantError.NON_ADMIN,
                "The Home Assistant token belongs to a non-admin user."
            )
        }
    }

    private fun sendImport(
        session: WebSocketSession,
        device: ActivatedDevice,
        mainStreamUrl: String?,
        subStreamUrl: String?
    ): ImportOutcome {
        val id = session.nextCommandId()
        session.send(HomeAssistantProtocol.importPayload(device, id, mainStreamUrl, subStreamUrl))
        val response = session.awaitResult(id, IMPORT_COMMAND_TIMEOUT_SECONDS)
        val result = response.getJSONObject("result")
            ?: throw invalidResponse("Home Assistant returned no import result.")
        result.getString("error")?.let { throw serverError(it) }
        return when (result.getString("outcome")) {
            "added" -> ImportOutcome.ADDED
            "updated" -> ImportOutcome.UPDATED
            "already_present" -> ImportOutcome.ALREADY_PRESENT
            else -> throw invalidResponse("Home Assistant returned an unknown import outcome.")
        }
    }

    private fun awaitLoadedEntry(
        session: WebSocketSession,
        device: ActivatedDevice,
        outcome: ImportOutcome
    ): ImportResult {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(IMPORT_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            val response = session.callCommand("config_entries/get")
            val entries = response.getJSONArray("result")
                ?: throw invalidResponse("Home Assistant returned no config-entry list.")
            for (index in 0 until entries.size) {
                val entry = entries.getJSONObject(index)
                if (entry.getString("domain") != "tuya_local") continue
                val uniqueId = entry.getString("unique_id")
                val matches = if (uniqueId != null) {
                    uniqueId == device.deviceId
                } else {
                    entry.getString("title") == device.name
                }
                if (matches && entry.getString("state") == "loaded") {
                    val entryId = entry.getString("entry_id")
                        ?: throw invalidResponse("The loaded Tuya Local entry has no entry ID.")
                    return ImportResult(outcome, entryId, loaded = true)
                }
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw HomeAssistantException(
                    HomeAssistantError.INTERRUPTED,
                    "Config-entry loading poll was interrupted."
                )
            }
        }
        throw HomeAssistantException(
            HomeAssistantError.ENTRY_NOT_LOADED,
            "Timed out waiting for the Tuya Local config entry to finish loading."
        )
    }

    private fun validateDevice(device: ActivatedDevice) {
        if (device.deviceId.isBlank() || device.localKey.isBlank() ||
            device.name.isBlank() || device.productId.isBlank()
        ) {
            throw HomeAssistantException(
                HomeAssistantError.INVALID_FORMAT,
                "Device ID, local key, name, and product ID are required for import."
            )
        }
    }

    private class WebSocketSession(
        httpClient: OkHttpClient,
        request: Request,
        val adminToken: String
    ) : Closeable {
        private val messages = LinkedBlockingQueue<Any>()
        private val webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                messages.offer(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                messages.offer(ConnectionFailure)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                messages.offer(ConnectionClosed)
            }
        })
        private var commandId = 0L

        fun nextCommandId(): Long = ++commandId

        fun send(payload: Map<String, Any?>) {
            if (!webSocket.send(JSON.toJSONString(payload))) {
                throw HomeAssistantException(
                    HomeAssistantError.CONNECTION_FAILED,
                    "Home Assistant WebSocket is not accepting messages."
                )
            }
        }

        fun callCommand(type: String, fields: Map<String, Any?> = emptyMap()): JSONObject {
            val id = nextCommandId()
            send(mapOf("id" to id, "type" to type) + fields)
            return awaitResult(id)
        }

        fun awaitResult(id: Long, timeoutSeconds: Long = MESSAGE_TIMEOUT_SECONDS): JSONObject {
            while (true) {
                val message = nextJson(timeoutSeconds)
                if (message.getString("type") == "result" && message.getLongValue("id") == id) {
                    if (!message.getBooleanValue("success")) {
                        val code = message.getJSONObject("error")?.getString("code")
                        throw serverError(code)
                    }
                    return message
                }
            }
        }

        fun nextJson(timeoutSeconds: Long = MESSAGE_TIMEOUT_SECONDS): JSONObject {
            val message = nextMessage(timeoutSeconds)
            return try {
                JSON.parseObject(message)
            } catch (e: JSONException) {
                throw invalidResponse("Home Assistant returned invalid JSON.")
            }
        }

        private fun nextMessage(timeoutSeconds: Long): String {
            val event = try {
                messages.poll(timeoutSeconds, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw HomeAssistantException(
                    HomeAssistantError.INTERRUPTED,
                    "Home Assistant WebSocket wait was interrupted."
                )
            }
            return when (event) {
                null -> throw HomeAssistantException(
                    HomeAssistantError.TIMEOUT,
                    "Timed out waiting for Home Assistant."
                )
                ConnectionFailure -> throw HomeAssistantException(
                    HomeAssistantError.CONNECTION_FAILED,
                    "Could not connect to the Home Assistant WebSocket."
                )
                ConnectionClosed -> throw HomeAssistantException(
                    HomeAssistantError.CONNECTION_FAILED,
                    "Home Assistant closed the WebSocket connection."
                )
                is String -> event
                else -> throw IllegalStateException("Unknown WebSocket event")
            }
        }

        override fun close() {
            webSocket.close(1000, null)
        }
    }

    private object ConnectionFailure
    private object ConnectionClosed

    private companion object {
        const val MESSAGE_TIMEOUT_SECONDS = 15L
        const val IMPORT_COMMAND_TIMEOUT_SECONDS = 90L
        const val IMPORT_TIMEOUT_SECONDS = 60L
        const val RECONNECT_WINDOW_SECONDS = 15L
        const val RECONNECT_DELAY_MILLIS = 500L
        const val POLL_INTERVAL_MILLIS = 1000L
        val DEFAULT_HTTP_CLIENT: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

internal object HomeAssistantProtocol {
    /** Home Assistant's slug: lower case, accents dropped, every other run of characters becomes one underscore. */
    fun entitySlug(name: String): String =
        java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')

    /** Home Assistant numbers an entity ID that is already in use: `_2`, `_3`, and so on. */
    fun freeEntityId(wanted: String, taken: Set<String>): String {
        if (wanted !in taken) return wanted
        var number = 2
        while ("${wanted}_$number" in taken) number++
        return "${wanted}_$number"
    }

    fun webSocketUrl(hassUrl: String): String {
        val base = hassUrl.trim().trimEnd('/')
        val converted = when {
            base.startsWith("http://", ignoreCase = true) ->
                "ws://" + base.substring(7)
            base.startsWith("https://", ignoreCase = true) ->
                "wss://" + base.substring(8)
            else -> throw HomeAssistantException(
                HomeAssistantError.INVALID_FORMAT,
                "Home Assistant URL must use http or https."
            )
        }
        return converted + "/api/websocket"
    }

    fun importPayload(
        device: ActivatedDevice,
        id: Long,
        mainStreamUrl: String? = null,
        subStreamUrl: String? = null
    ): Map<String, Any?> {
        val protocolVersion = device.protocolVersion?.trim()
            ?.takeIf { SUPPORTED_PROTOCOL_VERSION.matches(it) }
            ?: "auto"
        val payload = linkedMapOf<String, Any?>(
            "id" to id,
            "type" to "tuya_local/import_device",
            "import_version" to 1,
            "device_id" to device.deviceId,
            "local_key" to device.localKey,
            "name" to device.name,
            "product_id" to device.productId,
            "protocol_version" to protocolVersion
        )
        device.category?.trim()?.takeIf { it.isNotEmpty() }?.let { payload["category"] = it }
        device.ip?.trim()?.takeIf(::isRfc1918Address)?.let { payload["ip"] = it }
        mainStreamUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { payload["main_stream_url"] = it }
        subStreamUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { payload["sub_stream_url"] = it }
        return payload
    }

    fun isRfc1918Address(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { part ->
            if (part.isEmpty() || part.any { !it.isDigit() }) return false
            val octet = part.toIntOrNull() ?: return false
            if (octet !in 0..255) return false
            octet
        }
        return octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }

    fun isSupportedProtocolVersion(value: String): Boolean =
        SUPPORTED_PROTOCOL_VERSION.matches(value)

    fun onvifConfigurePayload(
        name: String,
        host: String,
        port: Int,
        username: String,
        password: String
    ): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "host" to host,
        "port" to port,
        "username" to username,
        "password" to password
    )

    fun onvifReconfigureAddress(form: JSONObject): Pair<String, Int> {
        val schema = form.getJSONArray("data_schema")
            ?: throw invalidResponse("The ONVIF reconfigure form has no schema.")
        val fields = (0 until schema.size).map { schema.getJSONObject(it) }
        fun suggested(name: String): Any = fields.single { it.getString("name") == name }
            .getJSONObject("description")?.get("suggested_value")
            ?: throw invalidResponse("The ONVIF reconfigure form has no current $name.")
        return suggested("host") as String to suggested("port") as Int
    }

    // Errors and aborts produced by the ONVIF configure step (homeassistant/components/onvif/config_flow.py).
    fun onvifFlowFailure(response: JSONObject): HomeAssistantException {
        val message = when (val type = response.getString("type")) {
            "form" -> {
                val errors = response.getJSONObject("errors")
                if (errors == null || errors.isEmpty()) {
                    "Home Assistant showed an unexpected ONVIF setup step (${response.getString("step_id")})."
                } else {
                    errors.entries.joinToString(" ") { (field, key) ->
                        when (key) {
                            "auth_failed" ->
                                "Home Assistant could not log in to the ONVIF device: ${onvifErrorDetail(response, key)} (auth_failed)."
                            "onvif_error" ->
                                "Home Assistant could not set up the ONVIF device: ${onvifErrorDetail(response, key)} (onvif_error)."
                            "no_onvif_service" ->
                                "No ONVIF service answered on that host and port. Check the port number (no_onvif_service)."
                            else ->
                                "Home Assistant rejected the ONVIF configuration ($field: $key)."
                        }
                    }
                }
            }
            "abort" -> when (val reason = response.getString("reason")) {
                "no_h264" ->
                    "The ONVIF device offers no H.264 stream. Check its profile configuration (no_h264)."
                "no_mac" ->
                    "Home Assistant could not read a MAC address or serial number from the ONVIF device (no_mac)."
                else ->
                    "Home Assistant aborted the ONVIF setup ($reason)."
            }
            else -> "Home Assistant returned an unexpected ONVIF setup result ($type)."
        }
        return HomeAssistantException(HomeAssistantError.SERVER_ERROR, message)
    }

    private fun onvifErrorDetail(response: JSONObject, key: Any?): String =
        response.getJSONObject("description_placeholders")?.getString("error")
            ?: throw invalidResponse("Home Assistant returned the ONVIF $key error without its detail.")

    private val SUPPORTED_PROTOCOL_VERSION = Regex("3\\.\\d+")
}

internal fun serverError(code: String?): HomeAssistantException {
    val error = when (code) {
        "unknown_command" -> HomeAssistantError.UNKNOWN_COMMAND
        "unauthorized", "auth_invalid" -> HomeAssistantError.UNAUTHORIZED
        "invalid_format" -> HomeAssistantError.INVALID_FORMAT
        "handshake_failed" -> HomeAssistantError.HANDSHAKE_FAILED
        "unsupported_profile" -> HomeAssistantError.UNSUPPORTED_PROFILE
        "unsupported_protocol" -> HomeAssistantError.UNSUPPORTED_PROTOCOL
        "discovery_failed" -> HomeAssistantError.DISCOVERY_FAILED
        else -> HomeAssistantError.SERVER_ERROR
    }
    val message = when (error) {
        HomeAssistantError.UNKNOWN_COMMAND ->
            "The Home Assistant Tuya Local integration does not support device import."
        HomeAssistantError.UNAUTHORIZED ->
            "Home Assistant rejected the import request as unauthorized."
        HomeAssistantError.INVALID_FORMAT ->
            "Home Assistant rejected the device import format."
        HomeAssistantError.HANDSHAKE_FAILED ->
            "The local device handshake failed during import."
        HomeAssistantError.UNSUPPORTED_PROFILE ->
            "The device profile is not supported by Tuya Local."
        HomeAssistantError.UNSUPPORTED_PROTOCOL ->
            "The device protocol is not supported by Tuya Local."
        HomeAssistantError.DISCOVERY_FAILED ->
            "Home Assistant could not find the device on the local network. " +
                "Check that it is powered on and connected to the same LAN."
        else ->
            "Home Assistant could not import the device."
    }
    return HomeAssistantException(error, message)
}

private fun invalidResponse(message: String) = HomeAssistantException(
    HomeAssistantError.INVALID_RESPONSE,
    message
)
