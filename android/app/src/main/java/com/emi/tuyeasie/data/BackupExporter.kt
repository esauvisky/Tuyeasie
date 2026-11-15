package com.emi.tuyeasie.data

import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a JSON backup where identifiers stay readable and every secret is sealed with AES-256-GCM.
 * With a [passphrase] the key is derived from it (PBKDF2-HMAC-SHA256, salt and iteration count stored in the file).
 * Without one the app-wide key is used, which anyone with the APK can extract.
 */
class BackupExporter(private val store: SecureSettingsStore) {
    fun export(passphrase: String?, accounts: List<SavedTuyaAccount>): String {
        val random = SecureRandom()
        val encoder = Base64.getEncoder()
        val (key, encryption) = if (passphrase != null) {
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            val keySpec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
            val derived = SecretKeySpec(SecretKeyFactory.getInstance(KDF).generateSecret(keySpec).encoded, "AES")
            keySpec.clearPassword()
            derived to JSONObject().put("cipher", "AES-256-GCM").put("kdf", KDF)
                .put("iterations", PBKDF2_ITERATIONS).put("salt", encoder.encodeToString(salt))
        } else {
            SecretKeySpec(EXPORT_KEY, "AES") to JSONObject().put("cipher", "AES-256-GCM").put("keyScope", "app-wide")
        }
        fun seal(value: String): JSONObject {
            val iv = ByteArray(IV_BYTES).also(random::nextBytes)
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            return JSONObject().put("iv", encoder.encodeToString(iv))
                .put("ciphertext", encoder.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8))))
        }
        fun schema(caps: DeviceCapabilities?) = caps?.let {
            JSONObject().put("dataPoints", JSONArray(it.dataPoints.map { dp ->
                JSONObject().put("id", dp.id).put("code", dp.code).put("name", dp.name).put("mode", dp.mode)
                    .put("type", dp.type).put("propertyJson", dp.propertyJson).put("schemaType", dp.schemaType)
            })).put("mac", it.mac ?: JSONObject.NULL).put("tuyaLan", it.tuyaLan)
        } ?: JSONObject.NULL
        val settings = store.loadSettings()
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("createdAt", Instant.now().toString())
            .put("encryption", encryption)
            .put("accounts", JSONArray(accounts.map { account ->
                JSONObject().put("kind", account.kind.name).put("username", account.account.uid)
                    .put("password", seal(account.account.password))
            }))
            .put("settings", JSONObject().put("hassUrl", settings.hassUrl)
                .put("adminToken", seal(settings.adminToken)).put("countryCode", settings.countryCode)
                .put("homeWifiSsid", settings.homeWifiSsid).put("homeWifiPassword", seal(settings.homeWifiPassword)))
            .put("devices", JSONArray(store.loadCachedDeviceIds().map { deviceId ->
                JSONObject().put("deviceId", deviceId).put("capabilities", schema(store.loadCapabilities(deviceId)))
                    .put("integrations", JSONArray(store.loadIntegrationOptions(deviceId).map { option ->
                        JSONObject().put("key", option.key).put("handler", option.handler ?: JSONObject.NULL)
                            .put("entryId", option.entryId ?: JSONObject.NULL).put("deviceRegistryId", option.deviceRegistryId ?: JSONObject.NULL)
                            .put("openUrl", option.openUrl ?: JSONObject.NULL).put("status", option.status.name)
                    }))
            }))
            .put("onvif", JSONArray(store.loadOnvifRecords().map { record ->
                JSONObject().put("deviceId", record.deviceId).put("address", record.address)
                    .put("username", record.username).put("password", seal(record.password))
                    .put("mainUrl", seal(record.mainUrl)).put("subUrl", record.subUrl?.let(::seal) ?: JSONObject.NULL)
                    .put("entryIds", JSONArray(record.entryIds)).put("verified", record.verified)
            }))
            .toString(2)
    }

    private companion object {
        const val FORMAT = "tuyeasie-backup"
        const val VERSION = 2
        const val CIPHER = "AES/GCM/NoPadding"
        const val KDF = "PBKDF2WithHmacSHA256"
        const val PBKDF2_ITERATIONS = 210_000
        const val KEY_BITS = 256
        const val SALT_BYTES = 16
        private val EXPORT_KEY = byteArrayOf(
            0x54, 0x75, 0x79, 0x65, 0x61, 0x73, 0x69, 0x65,
            0x2d, 0x62, 0x61, 0x63, 0x6b, 0x75, 0x70, 0x2d,
            0x6b, 0x65, 0x79, 0x2d, 0x76, 0x31, 0x2d, 0x41,
            0x45, 0x53, 0x32, 0x35, 0x36, 0x21, 0x21, 0x21
        )
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
