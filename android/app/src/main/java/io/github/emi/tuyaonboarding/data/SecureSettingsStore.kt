package io.github.emi.tuyaonboarding.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

data class AppSettings(
    val hassUrl: String = "",
    val adminToken: String = "",
    val countryCode: String = "55",
    val homeWifiSsid: String = "",
    val homeWifiPassword: String = ""
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
)

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

    fun deletePendingDevice() {
        val committed = preferences.edit()
            .remove(KEY_DEVICE_ID)
            .remove(KEY_LOCAL_KEY)
            .remove(KEY_DEVICE_NAME)
            .remove(KEY_PRODUCT_ID)
            .remove(KEY_PROTOCOL_VERSION)
            .remove(KEY_CATEGORY)
            .remove(KEY_IP)
            .remove(KEY_IMPORT_STATE)
            .remove(KEY_LAST_ERROR)
            .commit()
        check(committed) { "Unable to delete encrypted device record" }
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
            device.localKey
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
