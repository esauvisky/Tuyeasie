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
    val homeWifiPassword: String = "",
    val feedDestination: String = "tuya_local",
    val go2rtcUrl: String = "",
    val go2rtcUsername: String = "",
    val go2rtcPassword: String = ""
)

data class ActivatedDevice(
    val deviceId: String,
    val localKey: String,
    val name: String,
    val productId: String,
    val protocolVersion: String?,
    val category: String?,
    val ip: String?
)

data class TuyaAccount(
    val uid: String,
    val password: String
) {
    override fun toString() = "TuyaAccount(uid=$uid, password=[redacted])"
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

enum class ImportState {
    PENDING,
    IMPORTED
}

data class PendingDeviceRecord(
    val device: ActivatedDevice,
    val state: ImportState,
    val lastError: String?
)

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

    fun loadSettings(): AppSettings = AppSettings(
        hassUrl = preferences.getString(KEY_HASS_URL, "").orEmpty(),
        adminToken = preferences.getString(KEY_ADMIN_TOKEN, "").orEmpty(),
        countryCode = preferences.getString(KEY_COUNTRY_CODE, "55").orEmpty(),
        homeWifiSsid = preferences.getString(KEY_WIFI_SSID, "").orEmpty(),
        homeWifiPassword = preferences.getString(KEY_WIFI_PASSWORD, "").orEmpty(),
        feedDestination = preferences.getString(KEY_FEED_DESTINATION, "tuya_local").orEmpty(),
        go2rtcUrl = preferences.getString(KEY_GO2RTC_URL, "").orEmpty(),
        go2rtcUsername = preferences.getString(KEY_GO2RTC_USERNAME, "").orEmpty(),
        go2rtcPassword = preferences.getString(KEY_GO2RTC_PASSWORD, "").orEmpty()
    )

    fun saveSettings(settings: AppSettings) {
        val committed = preferences.edit()
            .putString(KEY_HASS_URL, settings.hassUrl)
            .putString(KEY_ADMIN_TOKEN, settings.adminToken)
            .putString(KEY_COUNTRY_CODE, settings.countryCode)
            .putString(KEY_WIFI_SSID, settings.homeWifiSsid)
            .putString(KEY_WIFI_PASSWORD, settings.homeWifiPassword)
            .putString(KEY_FEED_DESTINATION, settings.feedDestination)
            .putString(KEY_GO2RTC_URL, settings.go2rtcUrl)
            .putString(KEY_GO2RTC_USERNAME, settings.go2rtcUsername)
            .putString(KEY_GO2RTC_PASSWORD, settings.go2rtcPassword)
            .commit()
        check(committed) { "Unable to persist encrypted settings" }
    }

    fun loadOrCreateTuyaAccount(): TuyaAccount {
        val uid = preferences.getString(KEY_TUYA_UID, null)
        if (uid != null) {
            return TuyaAccount(uid, checkNotNull(preferences.getString(KEY_TUYA_PASSWORD, null)) {
                "Stored Tuya account has no password"
            })
        }
        val account = TuyaAccount("tuyeasie${randomHex(12)}", randomHex(16))
        val committed = preferences.edit()
            .putString(KEY_TUYA_UID, account.uid)
            .putString(KEY_TUYA_PASSWORD, account.password)
            .commit()
        check(committed) { "Unable to persist Tuya account" }
        return account
    }

    fun loadCameraAccount(): TuyaAccount? {
        val uid = preferences.getString(KEY_CAMERA_EMAIL, null) ?: return null
        return TuyaAccount(uid, checkNotNull(preferences.getString(KEY_CAMERA_PASSWORD, null)) {
            "Stored camera account has no password"
        })
    }

    fun saveCameraAccount(account: TuyaAccount) {
        val editor = preferences.edit()
            .putString(KEY_CAMERA_EMAIL, account.uid)
            .putString(KEY_CAMERA_PASSWORD, account.password)
            .remove(KEY_EXTRA_ACCOUNTS)
        preferences.all.keys
            .filter { it.startsWith(KEY_EXTRA_PASSWORD_PREFIX) }
            .forEach { editor.remove(it) }
        val committed = editor.commit()
        check(committed) { "Unable to persist camera account" }
    }

    fun loadExistingTuyaAccount(): TuyaAccount? {
        val uid = preferences.getString(KEY_TUYA_UID, null) ?: return null
        return TuyaAccount(uid, checkNotNull(preferences.getString(KEY_TUYA_PASSWORD, null)) {
            "Stored Tuya account has no password"
        })
    }

    fun deleteGeneratedTuyaAccount() {
        val committed = preferences.edit().remove(KEY_TUYA_UID).remove(KEY_TUYA_PASSWORD).commit()
        check(committed) { "Unable to delete Tuya account" }
    }

    fun deleteCameraAccount() {
        val editor = preferences.edit()
            .remove(KEY_CAMERA_EMAIL)
            .remove(KEY_CAMERA_PASSWORD)
            .remove(KEY_EXTRA_ACCOUNTS)
        preferences.all.keys
            .filter { it.startsWith(KEY_EXTRA_PASSWORD_PREFIX) }
            .forEach { editor.remove(it) }
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
            username = preferences.getString(onvifKey(deviceId, "username"), "admin")!!,
            password = required("password"),
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
        val points = json.getJSONObject("dataPoints")
        return DeviceCapabilities(
            dataPoints = points.keys().asSequence().associateWith { points.getString(it) },
            mac = if (json.isNull("mac")) null else json.getString("mac"),
            tuyaLan = json.getBoolean("tuyaLan")
        )
    }

    fun saveCapabilities(deviceId: String, capabilities: DeviceCapabilities) {
        val json = JSONObject()
            .put("dataPoints", JSONObject(capabilities.dataPoints))
            .put("mac", capabilities.mac ?: JSONObject.NULL)
            .put("tuyaLan", capabilities.tuyaLan)
        val committed = preferences.edit().putString(KEY_CAPABILITIES_PREFIX + deviceId, json.toString()).commit()
        check(committed) { "Unable to persist device capabilities" }
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
            ip = json.getString("ip"),
            addressSource = AddressSource.valueOf(json.getString("addressSource")),
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
            .put("ip", report.ip)
            .put("addressSource", report.addressSource.name)
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

    /** Drops everything stored about an unpaired device. */
    fun forgetDevice(deviceId: String) {
        val editor = preferences.edit()
            .remove(KEY_CAPABILITIES_PREFIX + deviceId)
            .remove(KEY_NETWORK_PREFIX + deviceId)
        preferences.all.keys.filter { it.startsWith("$ONVIF_PREFIX${deviceId}_") }.forEach { editor.remove(it) }
        check(editor.commit()) { "Unable to forget device $deviceId" }
    }

    private fun onvifKey(deviceId: String, field: String) = "${ONVIF_PREFIX}${deviceId}_$field"

    private fun randomHex(bytes: Int): String =
        ByteArray(bytes).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    fun loadPendingDevice(): PendingDeviceRecord? {
        val deviceId = preferences.getString(KEY_DEVICE_ID, null) ?: return null
        return PendingDeviceRecord(
            device = ActivatedDevice(
                deviceId = deviceId,
                localKey = preferences.getString(KEY_LOCAL_KEY, null).orEmpty(),
                name = preferences.getString(KEY_DEVICE_NAME, null).orEmpty(),
                productId = preferences.getString(KEY_PRODUCT_ID, null).orEmpty(),
                protocolVersion = preferences.getString(KEY_PROTOCOL_VERSION, null),
                category = preferences.getString(KEY_CATEGORY, null),
                ip = preferences.getString(KEY_IP, null)
            ),
            state = ImportState.valueOf(
                preferences.getString(KEY_IMPORT_STATE, ImportState.PENDING.name)
                    ?: ImportState.PENDING.name
            ),
            lastError = preferences.getString(KEY_LAST_ERROR, null)
        )
    }

    fun savePendingDevice(device: ActivatedDevice) {
        savePendingDevice(PendingDeviceRecord(device, ImportState.PENDING, null))
    }

    fun savePendingDevice(record: PendingDeviceRecord) {
        val committed = preferences.edit()
            .putString(KEY_DEVICE_ID, record.device.deviceId)
            .putString(KEY_LOCAL_KEY, record.device.localKey)
            .putString(KEY_DEVICE_NAME, record.device.name)
            .putString(KEY_PRODUCT_ID, record.device.productId)
            .putString(KEY_PROTOCOL_VERSION, record.device.protocolVersion)
            .putString(KEY_CATEGORY, record.device.category)
            .putString(KEY_IP, record.device.ip)
            .putString(KEY_IMPORT_STATE, record.state.name)
            .putString(KEY_LAST_ERROR, sanitizeError(record.lastError, record.device))
            .commit()
        check(committed) { "Unable to persist encrypted device record" }
    }

    fun updatePendingDeviceState(state: ImportState, lastError: String? = null) {
        val current = checkNotNull(loadPendingDevice()) { "No pending device record" }
        savePendingDevice(current.copy(state = state, lastError = lastError))
    }

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
        const val KEY_FEED_DESTINATION = "feed_destination"
        const val KEY_GO2RTC_URL = "go2rtc_url"
        const val KEY_GO2RTC_USERNAME = "go2rtc_username"
        const val KEY_GO2RTC_PASSWORD = "go2rtc_password"
        const val KEY_TUYA_UID = "tuya_uid"
        const val KEY_TUYA_PASSWORD = "tuya_password"
        const val KEY_CAMERA_EMAIL = "camera_account_email"
        const val KEY_CAMERA_PASSWORD = "camera_account_password"
        const val KEY_EXTRA_ACCOUNTS = "extra_tuya_accounts"
        const val KEY_EXTRA_PASSWORD_PREFIX = "extra_tuya_password_"
        const val ONVIF_PREFIX = "onvif_"
        const val ONVIF_ADDRESS_SUFFIX = "_address"
        const val KEY_CAPABILITIES_PREFIX = "capabilities_"
        const val KEY_NETWORK_PREFIX = "network_"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_LOCAL_KEY = "device_local_key"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_PRODUCT_ID = "device_product_id"
        const val KEY_PROTOCOL_VERSION = "device_protocol_version"
        const val KEY_CATEGORY = "device_category"
        const val KEY_IP = "device_ip"
        const val KEY_IMPORT_STATE = "import_state"
        const val KEY_LAST_ERROR = "import_last_error"
        const val MAX_ERROR_LENGTH = 1000
    }
}
