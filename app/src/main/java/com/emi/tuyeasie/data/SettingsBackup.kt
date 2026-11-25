package com.emi.tuyeasie.data

import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The chosen file cannot be imported; the message is shown to the user. */
class SettingsFileException(message: String) : Exception(message)

/**
 * Writes and reads the settings file that moves Tuyeasie to another phone. Identifiers stay readable and every secret
 * is sealed with AES-256-GCM, so in the file identifiers stay readable and every secret is sealed with AES-256-GCM.
 * only names and IDs can be read. With a passphrase the key is derived from it (PBKDF2-HMAC-SHA256, salt and iteration count stored in the file).
 * Without one the app-wide key is used, which anyone with the APK can extract.
 */
class SettingsBackup(private val store: SecureSettingsStore) {
    fun export(passphrase: String?, accounts: List<SavedTuyaAccount>): String {
        val random = SecureRandom()
        val encoder = Base64.getEncoder()
        val (key, encryption) = if (passphrase != null) {
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            derive(passphrase, salt, PBKDF2_ITERATIONS) to JSONObject().put("cipher", "AES-256-GCM").put("kdf", KDF)
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
                    .put("password", seal(account.account.password)).put("countryCode", account.account.countryCode)
            }))
            .put("settings", JSONObject().put("hassUrl", settings.hassUrl)
                .put("adminToken", seal(settings.adminToken)).put("countryCode", settings.countryCode)
                .put("homeWifiSsid", settings.homeWifiSsid).put("homeWifiPassword", seal(settings.homeWifiPassword)))
            .put("devices", JSONArray(store.loadCachedDeviceIds().map { deviceId ->
                JSONObject().put("deviceId", deviceId).put("capabilities", schema(store.loadCapabilities(deviceId)))
                    .put("integrations", JSONArray(store.loadIntegrationOptions(deviceId).map { option ->
                        JSONObject().put("key", option.key).put("label", option.label).put("updatedAt", option.updatedAt)
                            .put("handler", option.handler ?: JSONObject.NULL)
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

    fun needsPassphrase(json: String): Boolean = read { parse(json).getJSONObject("encryption").has("kdf") }

    /** Replaces the settings, and each account, device and camera record the file holds. Nothing is written if it cannot be read. */
    fun restore(json: String, passphrase: String?) {
        val decoder = Base64.getDecoder()
        val writes = read {
            val root = parse(json)
            val encryption = root.getJSONObject("encryption")
            val key = if (encryption.has("kdf")) {
                derive(checkNotNull(passphrase) { "The file needs a passphrase" }, decoder.decode(encryption.getString("salt")), encryption.getInt("iterations"))
            } else SecretKeySpec(EXPORT_KEY, "AES")
            fun open(sealed: JSONObject): String {
                val cipher = Cipher.getInstance(CIPHER)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, decoder.decode(sealed.getString("iv"))))
                return try {
                    String(cipher.doFinal(decoder.decode(sealed.getString("ciphertext"))), Charsets.UTF_8)
                } catch (e: AEADBadTagException) {
                    throw SettingsFileException(if (passphrase != null) "Wrong passphrase." else "The file is damaged.")
                }
            }
            fun JSONObject.text(name: String): String? = if (isNull(name)) null else getString(name)
            fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
            val saved = root.getJSONObject("settings")
            val settings = AppSettings(
                hassUrl = saved.getString("hassUrl"),
                adminToken = open(saved.getJSONObject("adminToken")),
                countryCode = saved.getString("countryCode"),
                homeWifiSsid = saved.getString("homeWifiSsid"),
                homeWifiPassword = open(saved.getJSONObject("homeWifiPassword"))
            )
            // Format 3 kept no per-account code; its accounts signed in with the exporting phone's locale,
            // which is what settings.countryCode recorded.
            val legacyFormat = root.getInt("version") == LEGACY_VERSION
            val accounts = root.getJSONArray("accounts").objects().map {
                val countryCode = if (legacyFormat) settings.countryCode else it.getString("countryCode")
                TuyaAccountKind.valueOf(it.getString("kind")) to TuyaAccount(it.getString("username"), open(it.getJSONObject("password")), countryCode)
            }
            val devices = root.getJSONArray("devices").objects().map { device ->
                val capabilities = if (device.isNull("capabilities")) null else device.getJSONObject("capabilities").let { caps ->
                    DeviceCapabilities(
                        dataPoints = caps.getJSONArray("dataPoints").objects().map { dp ->
                            TuyaDataPointSchema(
                                dp.getString("id"), dp.getString("code"), dp.getString("name"), dp.getString("mode"),
                                dp.getString("type"), dp.getString("propertyJson"), dp.getString("schemaType")
                            )
                        },
                        mac = caps.text("mac"),
                        tuyaLan = caps.getBoolean("tuyaLan")
                    )
                }
                val integrations = device.getJSONArray("integrations").objects().map { option ->
                    HaIntegrationOption(
                        option.getString("key"), option.getString("label"), option.text("handler"),
                        IntegrationStatus.valueOf(option.getString("status")), option.text("entryId"), option.text("deviceRegistryId"),
                        option.text("openUrl"), null, option.getLong("updatedAt")
                    )
                }
                Triple(device.getString("deviceId"), capabilities, integrations)
            }
            val onvif = root.getJSONArray("onvif").objects().map { record ->
                val entryIds = record.getJSONArray("entryIds")
                OnvifRecord(
                    deviceId = record.getString("deviceId"),
                    address = record.getString("address"),
                    username = record.getString("username"),
                    password = open(record.getJSONObject("password")),
                    mainUrl = open(record.getJSONObject("mainUrl")),
                    subUrl = if (record.isNull("subUrl")) null else open(record.getJSONObject("subUrl")),
                    entryIds = (0 until entryIds.length()).map(entryIds::getString),
                    verified = record.getBoolean("verified")
                )
            }
            return@read {
                store.saveSettings(settings)
                accounts.forEach { (kind, account) ->
                    when (kind) {
                        TuyaAccountKind.TEMPORARY -> store.saveGeneratedTuyaAccount(account)
                        TuyaAccountKind.USER -> store.saveCameraAccount(account)
                    }
                    if (legacyFormat) store.markRegionGuessed(kind)
                }
                devices.forEach { (deviceId, capabilities, integrations) ->
                    if (capabilities != null) store.saveCapabilities(deviceId, capabilities)
                    store.saveIntegrationOptions(deviceId, integrations)
                }
                onvif.forEach(store::saveOnvifRecord)
            }
        }
        writes()
    }

    private fun parse(json: String): JSONObject {
        val root = JSONObject(json)
        if (root.optString("format") != FORMAT) throw SettingsFileException("This is not a Tuyeasie settings file.")
        val version = root.getInt("version")
        if (version != VERSION && version != LEGACY_VERSION) {
            throw SettingsFileException("This file is in format $version, and this version of Tuyeasie reads format $VERSION. Export it again with the same version.")
        }
        return root
    }

    private fun <T> read(block: () -> T): T = try {
        block()
    } catch (e: JSONException) {
        throw SettingsFileException("This is not a complete Tuyeasie settings file (${e.message}).")
    }

    private fun derive(passphrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val keySpec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_BITS)
        val derived = SecretKeySpec(SecretKeyFactory.getInstance(KDF).generateSecret(keySpec).encoded, "AES")
        keySpec.clearPassword()
        return derived
    }

    private companion object {
        const val FORMAT = "tuyeasie-backup"
        const val VERSION = 4
        const val LEGACY_VERSION = 3
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
