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
 * Builds a JSON backup where identifiers stay readable and every secret is sealed with AES-256-GCM
 * under a key derived from the user's passphrase (PBKDF2-HMAC-SHA256, salt and iteration count stored in the file).
 */
class BackupExporter(private val store: SecureSettingsStore) {
    fun export(passphrase: String, accounts: List<SavedTuyaAccount>): String {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val keySpec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        val key = SecretKeySpec(
            SecretKeyFactory.getInstance(KDF).generateSecret(keySpec).encoded,
            "AES"
        )
        keySpec.clearPassword()
        val encoder = Base64.getEncoder()

        fun seal(value: String): JSONObject {
            val iv = ByteArray(IV_BYTES).also(random::nextBytes)
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            return JSONObject()
                .put("iv", encoder.encodeToString(iv))
                .put("ciphertext", encoder.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8))))
        }

        val settings = store.loadSettings()
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("createdAt", Instant.now().toString())
            .put(
                "encryption",
                JSONObject()
                    .put("cipher", "AES-256-GCM")
                    .put("kdf", KDF)
                    .put("iterations", PBKDF2_ITERATIONS)
                    .put("salt", encoder.encodeToString(salt))
            )
            .put(
                "accounts",
                JSONArray(accounts.map {
                    JSONObject()
                        .put("kind", it.kind.name)
                        .put("username", it.account.uid)
                        .put("password", seal(it.account.password))
                })
            )
            .put(
                "settings",
                JSONObject()
                    .put("hassUrl", settings.hassUrl)
                    .put("adminToken", seal(settings.adminToken))
                    .put("countryCode", settings.countryCode)
                    .put("homeWifiSsid", settings.homeWifiSsid)
                    .put("homeWifiPassword", seal(settings.homeWifiPassword))
                    .put("feedDestination", settings.feedDestination)
                    .put("go2rtcUrl", settings.go2rtcUrl)
                    .put("go2rtcUsername", settings.go2rtcUsername)
                    .put("go2rtcPassword", seal(settings.go2rtcPassword))
            )
            .put(
                "onvif",
                JSONArray(store.loadOnvifRecords().map {
                    JSONObject()
                        .put("deviceId", it.deviceId)
                        .put("address", it.address)
                        .put("username", it.username)
                        .put("password", seal(it.password))
                        // Stream URLs embed the camera credentials.
                        .put("mainUrl", seal(it.mainUrl))
                        .put("subUrl", it.subUrl?.let(::seal) ?: JSONObject.NULL)
                        .put("destination", it.destination ?: JSONObject.NULL)
                        .put("registered", it.registered)
                        .put("verified", it.verified)
                        .put("capabilities", store.loadCapabilities(it.deviceId)?.let {
                            JSONObject()
                                .put("dataPoints", JSONObject(it.dataPoints))
                                .put("mac", it.mac ?: JSONObject.NULL)
                                .put("tuyaLan", it.tuyaLan)
                        } ?: JSONObject.NULL)
                })
            )
            .toString(2)
    }

    private companion object {
        const val FORMAT = "tuyeasie-backup"
        const val VERSION = 1
        const val KDF = "PBKDF2WithHmacSHA256"
        const val CIPHER = "AES/GCM/NoPadding"
        const val PBKDF2_ITERATIONS = 210_000
        const val KEY_BITS = 256
        const val SALT_BYTES = 16
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
