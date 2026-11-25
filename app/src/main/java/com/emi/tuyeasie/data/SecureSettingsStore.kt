package com.emi.tuyeasie.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

data class AppSettings(
    val hassUrl: String = "",
    val adminToken: String = "",
    val countryCode: String = "55",
    val homeWifiSsid: String = "",
    val homeWifiPassword: String = ""
) {
    // Home Assistant is optional: without a URL and token, devices are only paired with Tuya.
    val homeAssistantConfigured: Boolean
        get() = hassUrl.isNotBlank() && adminToken.isNotBlank()
}

data class ActivatedDevice(
    val deviceId: String,
    val localKey: String,
    val name: String,
    val productId: String,
    val protocolVersion: String?,
    val category: String?,
    val ip: String?
)

// Tuya's US data center, for every account Tuyeasie creates. Accounts only serve pairing and the odd
// cloud call afterwards; Home Assistant controls devices locally, so the region's latency never matters.
const val NEW_ACCOUNT_COUNTRY_CODE = "1"

// countryCode picks the Tuya data center the account lives in, so it is fixed when the account is created.
data class TuyaAccount(
    val uid: String,
    val password: String,
    val countryCode: String
) {
    override fun toString() = "TuyaAccount(uid=$uid, password=[redacted], countryCode=$countryCode)"
}

data class OnvifRecord(
    val deviceId: String,
    val address: String,
    val username: String,
    val password: String,
    val mainUrl: String,
    val subUrl: String?,
    val destination: String? = null,
    val registered: Boolean = false,
    val mainCodec: String? = null,
    val mainResolution: String? = null,
    val subCodec: String? = null,
    val subResolution: String? = null,
    // Home Assistant config entries created for this feed, removed again when the device is unpaired.
    val entryIds: List<String> = emptyList(),
    val verified: Boolean
) {
    override fun toString() =
        "OnvifRecord(deviceId=$deviceId, address=$address, username=$username, password=[redacted], " +
            "mainUrl=[redacted], subUrl=[redacted], destination=$destination, registered=$registered, " +
            "mainCodec=$mainCodec, mainResolution=$mainResolution, subCodec=$subCodec, subResolution=$subResolution, " +
            "entryIds=$entryIds, verified=$verified)"
}

class SecureSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val masterKey = MasterKey.Builder(appContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    private val preferences = EncryptedSharedPreferences.create(
        appContext,
        "tuya_onboarding_secure_settings",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    init {
        migrateAccountCountryCodes()
    }

    fun loadSettings(): AppSettings = AppSettings(
        hassUrl = preferences.getString(KEY_HASS_URL, "").orEmpty(),
        adminToken = preferences.getString(KEY_ADMIN_TOKEN, "").orEmpty(),
        countryCode = preferences.getString(KEY_COUNTRY_CODE, "55").orEmpty(),
        homeWifiSsid = preferences.getString(KEY_WIFI_SSID, "").orEmpty(),
        homeWifiPassword = preferences.getString(KEY_WIFI_PASSWORD, "").orEmpty()
    )

    fun saveSettings(settings: AppSettings) {
        val committed = preferences.edit()
            .putString(KEY_HASS_URL, settings.hassUrl)
            .putString(KEY_ADMIN_TOKEN, settings.adminToken)
            .putString(KEY_COUNTRY_CODE, settings.countryCode)
            .putString(KEY_WIFI_SSID, settings.homeWifiSsid)
            .putString(KEY_WIFI_PASSWORD, settings.homeWifiPassword)
            .commit()
        check(committed) { "Unable to persist encrypted settings" }
    }

    fun loadOrCreateTuyaAccount(): TuyaAccount {
        loadExistingTuyaAccount()?.let { return it }
        val account = TuyaAccount("tuyeasie${randomHex(12)}", randomHex(16), NEW_ACCOUNT_COUNTRY_CODE)
        val committed = preferences.edit()
            .putString(KEY_TUYA_UID, account.uid)
            .putString(KEY_TUYA_PASSWORD, account.password)
            .putString(KEY_TUYA_COUNTRY_CODE, account.countryCode)
            .commit()
        check(committed) { "Unable to persist Tuya account" }
        return account
    }

    fun saveGeneratedTuyaAccount(account: TuyaAccount) {
        val committed = preferences.edit()
            .putString(KEY_TUYA_UID, account.uid)
            .putString(KEY_TUYA_PASSWORD, account.password)
            .putString(KEY_TUYA_COUNTRY_CODE, account.countryCode)
            .remove(KEY_TUYA_REGION_GUESSED)
            .commit()
        check(committed) { "Unable to persist Tuya account" }
    }

    fun loadCameraAccount(): TuyaAccount? {
        val uid = preferences.getString(KEY_CAMERA_EMAIL, null) ?: return null
        return TuyaAccount(uid, checkNotNull(preferences.getString(KEY_CAMERA_PASSWORD, null)) {
            "Stored camera account has no password"
        }, checkNotNull(preferences.getString(KEY_CAMERA_COUNTRY_CODE, null)) {
            "Stored camera account has no country code"
        })
    }

    fun saveCameraAccount(account: TuyaAccount) {
        val editor = preferences.edit()
            .putString(KEY_CAMERA_EMAIL, account.uid)
            .putString(KEY_CAMERA_PASSWORD, account.password)
            .putString(KEY_CAMERA_COUNTRY_CODE, account.countryCode)
            .remove(KEY_CAMERA_REGION_GUESSED)
            .remove(KEY_EXTRA_ACCOUNTS)
        preferences.all.keys
            .filter { it.startsWith(KEY_EXTRA_PASSWORD_PREFIX) }
            .forEach { editor.remove(it) }
        val committed = editor.commit()
        check(committed) { "Unable to persist camera account" }
    }

    // A guessed code is the phone locale's, recorded for an account saved before codes were stored per account.
    fun isRegionGuessed(kind: TuyaAccountKind): Boolean = preferences.getBoolean(regionGuessedKey(kind), false)

    fun markRegionGuessed(kind: TuyaAccountKind) {
        check(preferences.edit().putBoolean(regionGuessedKey(kind), true).commit()) { "Unable to persist the guessed region" }
    }

    private fun regionGuessedKey(kind: TuyaAccountKind) = when (kind) {
        TuyaAccountKind.TEMPORARY -> KEY_TUYA_REGION_GUESSED
        TuyaAccountKind.USER -> KEY_CAMERA_REGION_GUESSED
    }

    // Tuya's code for the data center the account last signed in to, such as "EU"; null until it has signed in.
    fun loadDataCenter(accountUid: String): String? = preferences.getString(KEY_DATA_CENTER_PREFIX + accountUid, null)

    fun saveDataCenter(accountUid: String, regionCode: String) {
        check(preferences.edit().putString(KEY_DATA_CENTER_PREFIX + accountUid, regionCode).commit()) {
            "Unable to persist the Tuya data center"
        }
    }

    fun loadExistingTuyaAccount(): TuyaAccount? {
        val uid = preferences.getString(KEY_TUYA_UID, null) ?: return null
        return TuyaAccount(uid, checkNotNull(preferences.getString(KEY_TUYA_PASSWORD, null)) {
            "Stored Tuya account has no password"
        }, checkNotNull(preferences.getString(KEY_TUYA_COUNTRY_CODE, null)) {
            "Stored Tuya account has no country code"
        })
    }

    // Accounts saved before 2.6.1 have no stored code; they signed in with the phone's locale every time, so
    // that code is kept and marked guessed until a sign-in probe confirms the data center the account is in.
    private fun migrateAccountCountryCodes() {
        val legacy = listOf(
            Triple(KEY_TUYA_UID, KEY_TUYA_COUNTRY_CODE, KEY_TUYA_REGION_GUESSED),
            Triple(KEY_CAMERA_EMAIL, KEY_CAMERA_COUNTRY_CODE, KEY_CAMERA_REGION_GUESSED)
        ).filter { (uidKey, codeKey) -> preferences.contains(uidKey) && !preferences.contains(codeKey) }
        if (legacy.isEmpty()) return
        val code = CountryCodeProvider.current(appContext)
        val editor = preferences.edit()
        legacy.forEach { (_, codeKey, guessedKey) -> editor.putString(codeKey, code).putBoolean(guessedKey, true) }
        check(editor.commit()) { "Unable to persist Tuya account country codes" }
    }

    fun deleteGeneratedTuyaAccount() {
        val uid = preferences.getString(KEY_TUYA_UID, null)
        val editor = preferences.edit().remove(KEY_TUYA_UID).remove(KEY_TUYA_PASSWORD).remove(KEY_TUYA_COUNTRY_CODE)
            .remove(KEY_TUYA_REGION_GUESSED)
        if (uid != null) editor.remove(KEY_CACHED_DEVICES_PREFIX + uid).remove(KEY_DATA_CENTER_PREFIX + uid)
        val committed = editor.commit()
        check(committed) { "Unable to delete Tuya account" }
    }

    fun deleteCameraAccount() {
        val uid = preferences.getString(KEY_CAMERA_EMAIL, null)
        val editor = preferences.edit()
            .remove(KEY_CAMERA_EMAIL)
            .remove(KEY_CAMERA_PASSWORD)
            .remove(KEY_CAMERA_COUNTRY_CODE)
            .remove(KEY_CAMERA_REGION_GUESSED)
            .remove(KEY_EXTRA_ACCOUNTS)
        preferences.all.keys
            .filter { it.startsWith(KEY_EXTRA_PASSWORD_PREFIX) }
            .forEach { editor.remove(it) }
        if (uid != null) editor.remove(KEY_CACHED_DEVICES_PREFIX + uid).remove(KEY_DATA_CENTER_PREFIX + uid)
        val committed = editor.commit()
        check(committed) { "Unable to delete camera account" }
    }

    fun loadOnvifRecord(deviceId: String): OnvifRecord? {
        val address = preferences.getString(onvifKey(deviceId, "address"), null) ?: return null
        fun required(field: String) = checkNotNull(preferences.getString(onvifKey(deviceId, field), null)) {
            "Stored ONVIF record for $deviceId has no $field"
        }
        return OnvifRecord(
            deviceId = deviceId,
            address = address,
            // Records stored before usernames were kept all used the cameras' fixed "admin" user.
            username = preferences.getString(onvifKey(deviceId, "username"), "admin")!!.ifEmpty { "admin" },
            password = required("password").ifEmpty { "admin" },
            mainUrl = required("main_url"),
            // Single-stream devices have no substream.
            subUrl = preferences.getString(onvifKey(deviceId, "sub_url"), null),
            destination = preferences.getString(onvifKey(deviceId, "destination"), null),
            registered = preferences.getBoolean(onvifKey(deviceId, "registered"), false),
            mainCodec = preferences.getString(onvifKey(deviceId, "main_codec"), null),
            mainResolution = preferences.getString(onvifKey(deviceId, "main_resolution"), null),
            subCodec = preferences.getString(onvifKey(deviceId, "sub_codec"), null),
            subResolution = preferences.getString(onvifKey(deviceId, "sub_resolution"), null),
            entryIds = preferences.getString(onvifKey(deviceId, "entry_ids"), "")!!.split(',').filter { it.isNotEmpty() },
            // Records stored before this flag existed were only ever saved after full verification.
            verified = preferences.getBoolean(onvifKey(deviceId, "verified"), true)
        )
    }

    fun loadOnvifRecords(): List<OnvifRecord> =
        preferences.all.keys
            .filter { it.startsWith(ONVIF_PREFIX) && it.endsWith(ONVIF_ADDRESS_SUFFIX) }
            .map { key ->
                val deviceId = key.removePrefix(ONVIF_PREFIX).removeSuffix(ONVIF_ADDRESS_SUFFIX)
                checkNotNull(loadOnvifRecord(deviceId)) { "ONVIF record for $deviceId vanished while loading" }
            }

    fun loadPendingOnvifPassword(deviceId: String): String? =
        preferences.getString(onvifKey(deviceId, "pending_password"), null)

    fun loadPendingOnvifUsername(deviceId: String): String? =
        preferences.getString(onvifKey(deviceId, "pending_username"), null)

    /** Credentials entered for a device that has no ONVIF record yet; saving a record clears them. */
    fun saveOnvifCredentials(deviceId: String, username: String, password: String) {
        val committed = preferences.edit()
            .putString(onvifKey(deviceId, "pending_username"), username)
            .putString(onvifKey(deviceId, "pending_password"), password)
            .commit()
        check(committed) { "Unable to persist ONVIF credentials" }
    }

    fun savePendingOnvifPassword(deviceId: String, password: String) {
        val committed = preferences.edit()
            .putString(onvifKey(deviceId, "pending_password"), password)
            .commit()
        check(committed) { "Unable to persist pending ONVIF password" }
    }

    fun saveOnvifRecord(record: OnvifRecord) {
        val committed = preferences.edit()
            .remove(onvifKey(record.deviceId, "pending_password"))
            .remove(onvifKey(record.deviceId, "pending_username"))
            .putString(onvifKey(record.deviceId, "address"), record.address)
            .putString(onvifKey(record.deviceId, "username"), record.username)
            .putString(onvifKey(record.deviceId, "password"), record.password)
            .putString(onvifKey(record.deviceId, "main_url"), record.mainUrl)
            .apply {
                // A null string would be stored as a null entry, which the secret redaction can't read.
                if (record.subUrl == null) remove(onvifKey(record.deviceId, "sub_url"))
                else putString(onvifKey(record.deviceId, "sub_url"), record.subUrl)
            }
            .putString(onvifKey(record.deviceId, "destination"), record.destination)
            .putBoolean(onvifKey(record.deviceId, "registered"), record.registered)
            .putString(onvifKey(record.deviceId, "main_codec"), record.mainCodec)
            .putString(onvifKey(record.deviceId, "main_resolution"), record.mainResolution)
            .putString(onvifKey(record.deviceId, "sub_codec"), record.subCodec)
            .putString(onvifKey(record.deviceId, "sub_resolution"), record.subResolution)
            .putString(onvifKey(record.deviceId, "entry_ids"), record.entryIds.joinToString(","))
            .putBoolean(onvifKey(record.deviceId, "verified"), record.verified)
            .commit()
        check(committed) { "Unable to persist ONVIF record" }
    }

    fun loadCapabilities(deviceId: String): DeviceCapabilities? {
        val raw = preferences.getString(KEY_CAPABILITIES_PREFIX + deviceId, null) ?: return null
        val json = JSONObject(raw)
        return DeviceCapabilities(
            dataPoints = readDataPointSchemas(json),
            mac = if (json.isNull("mac")) null else json.getString("mac"),
            tuyaLan = json.getBoolean("tuyaLan")
        )
    }

    fun saveCapabilities(deviceId: String, capabilities: DeviceCapabilities) {
        val json = JSONObject()
            .put("dataPoints", writeDataPointSchemas(capabilities.dataPoints))
            .put("mac", capabilities.mac ?: JSONObject.NULL)
            .put("tuyaLan", capabilities.tuyaLan)
        val committed = preferences.edit().putString(KEY_CAPABILITIES_PREFIX + deviceId, json.toString()).commit()
        check(committed) { "Unable to persist device capabilities" }
    }

    fun loadIntegrationOptions(deviceId: String): List<HaIntegrationOption> {
        val raw = preferences.getString(KEY_INTEGRATION_PREFIX + deviceId, null)
        if (raw != null) {
            val array = JSONArray(raw)
            fun JSONObject.optText(key: String): String? = if (isNull(key)) null else getString(key)
            // 2.0.0 also stored detected-but-unaddable options as REMOVED; those are no longer tracked.
            return (0 until array.length()).map(array::getJSONObject).filter { it.getString("status") != "REMOVED" }.map { item ->
                HaIntegrationOption(
                    key = item.getString("key"),
                    label = item.getString("label"),
                    handler = item.optText("handler"),
                    status = IntegrationStatus.valueOf(item.getString("status")),
                    entryId = item.optText("entryId"),
                    deviceRegistryId = item.optText("deviceRegistryId"),
                    openUrl = item.optText("openUrl"),
                    error = item.optText("error"),
                    updatedAt = item.getLong("updatedAt")
                )
            }
        }
        val legacy = loadOnvifRecord(deviceId) ?: return emptyList()
        if (legacy.entryIds.isEmpty() && legacy.destination == null) return emptyList()
        val migrated = buildList {
            legacy.entryIds.forEachIndexed { index, entryId ->
                val key = when (legacy.destination) {
                    "tuya_local" -> IntegrationKeys.TUYA_LOCAL
                    "onvif" -> IntegrationKeys.ONVIF
                    "generic_camera" -> if (index == 0) IntegrationKeys.GENERIC else "generic_substream"
                    else -> if (index == 0) IntegrationKeys.GENERIC else "legacy_entry_$index"
                }
                add(HaIntegrationOption(
                    key, key, key.takeIf { it in setOf(IntegrationKeys.TUYA_LOCAL, IntegrationKeys.ONVIF, IntegrationKeys.GENERIC) },
                    IntegrationStatus.ADDED, entryId, null, null, null, System.currentTimeMillis()
                ))
            }
        }.distinctBy { it.key }
        saveIntegrationOptions(deviceId, migrated)
        return migrated
    }

    fun saveIntegrationOptions(deviceId: String, options: List<HaIntegrationOption>) {
        val json = JSONArray(options.map(::integrationOptionJson))
        val committed = preferences.edit().putString(KEY_INTEGRATION_PREFIX + deviceId, json.toString()).commit()
        check(committed) { "Unable to persist Home Assistant options for $deviceId" }
    }

    fun updateIntegrationOption(deviceId: String, option: HaIntegrationOption) {
        val current = loadIntegrationOptions(deviceId)
        val updated = if (current.any { it.key == option.key }) current.map { if (it.key == option.key) option else it }
        else current + option
        saveIntegrationOptions(deviceId, updated)
    }

    fun integrationEntryIds(deviceId: String): List<String> =
        (loadIntegrationOptions(deviceId).mapNotNull { it.entryId } + loadOnvifRecord(deviceId)?.entryIds.orEmpty()).distinct()

    private fun integrationOptionJson(option: HaIntegrationOption) = JSONObject()
        .put("key", option.key)
        .put("label", option.label)
        .put("handler", option.handler ?: JSONObject.NULL)
        .put("status", option.status.name)
        .put("entryId", option.entryId ?: JSONObject.NULL)
        .put("deviceRegistryId", option.deviceRegistryId ?: JSONObject.NULL)
        .put("openUrl", option.openUrl ?: JSONObject.NULL)
        .put("error", option.error ?: JSONObject.NULL)
        .put("updatedAt", option.updatedAt)

    private fun writeDataPointSchemas(points: List<TuyaDataPointSchema>) = JSONArray(points.map {
        JSONObject()
            .put("id", it.id)
            .put("code", it.code)
            .put("name", it.name)
            .put("mode", it.mode)
            .put("type", it.type)
            .put("propertyJson", it.propertyJson)
            .put("schemaType", it.schemaType)
    })

    private fun readDataPointSchemas(json: JSONObject): List<TuyaDataPointSchema> {
        val raw = json.get("dataPoints")
        if (raw is JSONObject) {
            return raw.keys().asSequence().map { code ->
                TuyaDataPointSchema(raw.getString(code), code, code, "ro", "unknown", "{}", "")
            }.toList()
        }
        val points = raw as JSONArray
        return (0 until points.length()).map { index ->
            val point = points.getJSONObject(index)
            TuyaDataPointSchema(
                id = point.getString("id"),
                code = point.getString("code"),
                name = point.getString("name"),
                mode = point.getString("mode"),
                type = point.getString("type"),
                propertyJson = point.getString("propertyJson"),
                schemaType = point.getString("schemaType")
            )
        }
    }

    fun loadNetworkReport(deviceId: String): NetworkReport? {
        val raw = preferences.getString(KEY_NETWORK_PREFIX + deviceId, null) ?: return null
        val json = JSONObject(raw)
        fun JSONObject.optText(name: String): String? = if (isNull(name)) null else getString(name)
        fun streams(array: JSONArray) = (0 until array.length()).map { index ->
            val stream = array.getJSONObject(index)
            DetectedStream(
                name = stream.getString("name"),
                url = stream.getString("url"),
                codec = stream.optText("codec"),
                resolution = stream.optText("resolution"),
                source = StreamSource.valueOf(stream.getString("source"))
            )
        }
        val ports = json.getJSONArray("openPorts")
        val onvif = if (json.isNull("onvif")) null else json.getJSONObject("onvif")
        return NetworkReport(
            ip = json.optText("ip"),
            addressSource = json.optText("addressSource")?.let(AddressSource::valueOf),
            scannedAt = json.getLong("scannedAt"),
            openPorts = (0 until ports.length()).map { index ->
                val port = ports.getJSONObject(index)
                OpenPort(port.getInt("port"), port.getString("service"), port.getString("description"), port.optText("detail"))
            },
            onvif = onvif?.let {
                OnvifInfo(
                    serviceUrl = it.getString("serviceUrl"),
                    manufacturer = it.optText("manufacturer"),
                    model = it.optText("model"),
                    firmware = it.optText("firmware"),
                    mac = it.optText("mac"),
                    authRequired = it.getBoolean("authRequired"),
                    streams = streams(it.getJSONArray("streams"))
                )
            },
            onvifError = json.optText("onvifError"),
            portScanStreams = streams(json.getJSONArray("portScanStreams"))
        )
    }

    fun saveNetworkReport(deviceId: String, report: NetworkReport) {
        fun Any?.orNull(): Any = this ?: JSONObject.NULL
        fun streams(list: List<DetectedStream>) = JSONArray(list.map {
            JSONObject()
                .put("name", it.name)
                .put("url", it.url)
                .put("codec", it.codec.orNull())
                .put("resolution", it.resolution.orNull())
                .put("source", it.source.name)
        })
        val json = JSONObject()
            .put("ip", report.ip ?: JSONObject.NULL)
            .put("addressSource", report.addressSource?.name ?: JSONObject.NULL)
            .put("scannedAt", report.scannedAt)
            .put("openPorts", JSONArray(report.openPorts.map {
                JSONObject()
                    .put("port", it.port)
                    .put("service", it.service)
                    .put("description", it.description)
                    .put("detail", it.detail.orNull())
            }))
            .put("onvif", report.onvif?.let {
                JSONObject()
                    .put("serviceUrl", it.serviceUrl)
                    .put("manufacturer", it.manufacturer.orNull())
                    .put("model", it.model.orNull())
                    .put("firmware", it.firmware.orNull())
                    .put("mac", it.mac.orNull())
                    .put("authRequired", it.authRequired)
                    .put("streams", streams(it.streams))
            }.orNull())
            .put("onvifError", report.onvifError.orNull())
            .put("portScanStreams", streams(report.portScanStreams))
        val committed = preferences.edit().putString(KEY_NETWORK_PREFIX + deviceId, json.toString()).commit()
        check(committed) { "Unable to persist network report" }
    }

    fun loadCachedDeviceIds(): Set<String> = preferences.all.keys
        .filter { it.startsWith(KEY_CAPABILITIES_PREFIX) }
        .map { it.removePrefix(KEY_CAPABILITIES_PREFIX) }
        .toSet()

    fun loadCachedDevices(accountUid: String): List<TuyaManagedDevice>? {
        val raw = preferences.getString(KEY_CACHED_DEVICES_PREFIX + accountUid, null) ?: return null
        val array = JSONArray(raw)
        fun JSONObject.optText(name: String): String? = if (isNull(name)) null else getString(name)
        return (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            val deviceId = obj.getString("deviceId")
            val name = obj.getString("name")
            val productId = obj.getString("productId")
            val points = readDataPointSchemas(obj)
            val capabilities = DeviceCapabilities(
                dataPoints = points,
                mac = obj.optText("mac"),
                tuyaLan = obj.getBoolean("tuyaLan")
            )
            TuyaManagedDevice(
                deviceId = deviceId,
                name = name,
                productId = productId,
                homeName = obj.getString("homeName"),
                iconUrl = obj.optText("iconUrl"),
                capabilities = capabilities,
                activated = ActivatedDevice(
                    deviceId = deviceId,
                    localKey = obj.getString("localKey"),
                    name = name,
                    productId = productId,
                    protocolVersion = obj.optText("protocolVersion"),
                    category = obj.optText("category"),
                    ip = obj.optText("ip")
                )
            )
        }
    }

    fun saveCachedDevices(accountUid: String, devices: List<TuyaManagedDevice>) {
        fun Any?.orNull(): Any = this ?: JSONObject.NULL
        val array = JSONArray(devices.map { device ->
            saveCapabilities(device.deviceId, device.capabilities)
            JSONObject()
                .put("deviceId", device.deviceId)
                .put("name", device.name)
                .put("productId", device.productId)
                .put("homeName", device.homeName)
                .put("iconUrl", device.iconUrl.orNull())
                .put("localKey", device.activated.localKey)
                .put("protocolVersion", device.activated.protocolVersion.orNull())
                .put("category", device.activated.category.orNull())
                .put("ip", device.activated.ip.orNull())
                .put("dataPoints", writeDataPointSchemas(device.capabilities.dataPoints))
                .put("mac", device.capabilities.mac.orNull())
                .put("tuyaLan", device.capabilities.tuyaLan)
        })
        val committed = preferences.edit().putString(KEY_CACHED_DEVICES_PREFIX + accountUid, array.toString()).commit()
        check(committed) { "Unable to persist cached devices for $accountUid" }
    }

    /** Drops everything stored about an unpaired device. */
    fun forgetDevice(deviceId: String) {
        val editor = preferences.edit()
            .remove(KEY_CAPABILITIES_PREFIX + deviceId)
            .remove(KEY_NETWORK_PREFIX + deviceId)
            .remove(KEY_INTEGRATION_PREFIX + deviceId)
        preferences.all.keys.filter { it.startsWith("$ONVIF_PREFIX${deviceId}_") }.forEach { editor.remove(it) }
        preferences.all.keys.filter { it.startsWith(KEY_CACHED_DEVICES_PREFIX) }.forEach { key ->
            val raw = preferences.getString(key, null) ?: return@forEach
            val array = JSONArray(raw)
            val kept = JSONArray()
            for (index in 0 until array.length()) {
                val obj = array.getJSONObject(index)
                if (obj.getString("deviceId") != deviceId) kept.put(obj)
            }
            editor.putString(key, kept.toString())
        }
        check(editor.commit()) { "Unable to forget device $deviceId" }
    }

    private fun onvifKey(deviceId: String, field: String) = "${ONVIF_PREFIX}${deviceId}_$field"

    private fun randomHex(bytes: Int): String =
        ByteArray(bytes).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    private fun sanitizeError(error: String?, device: ActivatedDevice): String? {
        if (error == null) return null
        val settings = loadSettings()
        var sanitized: String = error
        listOf(
            settings.adminToken,
            settings.homeWifiPassword,
            settings.homeWifiSsid,
            device.localKey,
            loadCameraAccount()?.password.orEmpty(),
            preferences.getString(KEY_TUYA_PASSWORD, null).orEmpty()
        ).plus(
            preferences.all
                .filterKeys {
                    it.startsWith(ONVIF_PREFIX) &&
                        (it.endsWith("_password") || it.endsWith("_main_url") || it.endsWith("_sub_url"))
                }
                .values.map { it as String }
        ).filter { it.isNotEmpty() }
            .forEach { secret -> sanitized = sanitized.replace(secret, "[redacted]") }
        return sanitized.take(MAX_ERROR_LENGTH)
    }

    private companion object {
        const val KEY_HASS_URL = "hass_url"
        const val KEY_ADMIN_TOKEN = "hass_admin_token"
        const val KEY_COUNTRY_CODE = "country_code"
        const val KEY_WIFI_SSID = "home_wifi_ssid"
        const val KEY_WIFI_PASSWORD = "home_wifi_password"
        const val KEY_TUYA_UID = "tuya_uid"
        const val KEY_TUYA_PASSWORD = "tuya_password"
        const val KEY_TUYA_COUNTRY_CODE = "tuya_country_code"
        const val KEY_CAMERA_EMAIL = "camera_account_email"
        const val KEY_CAMERA_PASSWORD = "camera_account_password"
        const val KEY_CAMERA_COUNTRY_CODE = "camera_account_country_code"
        const val KEY_TUYA_REGION_GUESSED = "tuya_country_code_guessed"
        const val KEY_CAMERA_REGION_GUESSED = "camera_account_country_code_guessed"
        const val KEY_DATA_CENTER_PREFIX = "data_center_"
        const val KEY_EXTRA_ACCOUNTS = "extra_tuya_accounts"
        const val KEY_EXTRA_PASSWORD_PREFIX = "extra_tuya_password_"
        const val ONVIF_PREFIX = "onvif_"
        const val ONVIF_ADDRESS_SUFFIX = "_address"
        const val KEY_CAPABILITIES_PREFIX = "capabilities_"
        const val KEY_NETWORK_PREFIX = "network_"
        const val KEY_CACHED_DEVICES_PREFIX = "cached_devices_"
        const val KEY_INTEGRATION_PREFIX = "ha_integrations_"
        const val MAX_ERROR_LENGTH = 1000
    }
}
