package com.emi.tuyeasie.data

import android.content.Context
import android.util.Log
import com.alibaba.fastjson.JSON
import com.thingclips.smart.home.sdk.ThingHomeSdk
import com.thingclips.smart.sdk.api.IDevListener
import com.thingclips.smart.sdk.api.IResultCallback
import com.thingclips.smart.sdk.api.IThingDevice
import com.emi.tuyeasie.hass.HomeAssistantProtocol
import java.io.IOException
import java.security.SecureRandom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CameraOnvifException(val stage: String, message: String, cause: Throwable? = null) :
    Exception("$stage: $message", cause)

class CameraOnvifRepository(private val context: Context) {
    suspend fun configure(
        device: ActivatedDevice,
        existingPassword: String?,
        targetPassword: String?,
        onProgress: (String) -> Unit,
        savePassword: suspend (String) -> Unit
    ): OnvifRecord {
        val camera = Camera(
            ThingHomeSdk.newDeviceInstance(device.deviceId),
            device.deviceId,
            onvifDps(device.deviceId),
            onProgress
        )
        val dps = camera.dps
        val attemptedPasswords = mutableSetOf<String>()
        val verified: VerifiedOnvif
        try {
            awaitCallback(camera, "ONVIF initial state read") { callback ->
                camera.sdk.getDpList(listOf(dps.switch, dps.passwordChanged, dps.ipAddress), callback)
            }
            val initialValues = deviceBean(camera).dps
            val wasSwitchOn = initialValues[dps.switch] == true
            val wasPasswordChanged = initialValues[dps.passwordChanged] == true
            // Some camera firmware rejects a password change while ONVIF is switched on, so turn it off first.
            if (wasSwitchOn) {
                onProgress("Turning ONVIF off before setting the password…")
                publish(camera, "ONVIF switch off (DP ${dps.switch})", """{"${dps.switch}":false}""")
                awaitDp(camera, "ONVIF switch off confirmation (DP ${dps.switch})", dps.switch, false)
            }
            onProgress("Setting the ONVIF password…")
            val activePassword = ensurePassword(
                camera = camera,
                existingPassword = existingPassword,
                targetPassword = targetPassword,
                wasPasswordChanged = wasPasswordChanged,
                attemptedPasswords = attemptedPasswords,
                savePassword = savePassword
            )
            Log.d(TAG, "onvif_change_pwd confirmed, turning ONVIF on")
            onProgress("Turning ONVIF on…")
            publish(camera, "ONVIF switch (DP ${dps.switch})", """{"${dps.switch}":true}""")
            awaitDp(camera, "ONVIF switch on confirmation (DP ${dps.switch})", dps.switch, true)
            // The camera only (re)starts its ONVIF and RTSP servers when the IP mode actually changes, so flip it to
            // the other mode and back. Forcing dynamic then static does nothing on the first step when the camera is
            // already dynamic, and it would leave a dynamic camera on static.
            val mode = readDp(camera, "ONVIF IP mode read (DP ${dps.ipType})", dps.ipType)
            if (mode != "0" && mode != "1") {
                throw CameraOnvifException(
                    "ONVIF IP mode read (DP ${dps.ipType})",
                    "camera reported IP mode '$mode', expected 0 or 1"
                )
            }
            val flipped = if (mode == "0") "1" else "0"
            onProgress("Toggling the camera's IP mode to start ONVIF…")
            publish(camera, "ONVIF IP mode toggle (DP ${dps.ipType})", """{"${dps.ipType}":"$flipped"}""")
            awaitDp(camera, "ONVIF IP mode toggle confirmation (DP ${dps.ipType})", dps.ipType, flipped)
            delay(1500)
            onProgress("Restoring the camera's IP mode…")
            publish(camera, "ONVIF IP mode restore (DP ${dps.ipType})", """{"${dps.ipType}":"$mode"}""")
            awaitDp(camera, "ONVIF IP mode restore confirmation (DP ${dps.ipType})", dps.ipType, mode)
            delay(1500)
            onProgress("Waiting for the camera's ONVIF and RTSP streams to come online…")
            verified = verifyState(camera, activePassword)
        } catch (e: CameraOnvifException) {
            val message = checkNotNull(e.message)
            var sanitized = message
            (attemptedPasswords + listOfNotNull(existingPassword, targetPassword))
                .filter { it.isNotEmpty() }
                .forEach { secret -> sanitized = sanitized.replace(secret, "[redacted]") }
            if (sanitized == message) throw e
            // The cause is omitted: it carries the unredacted message and would leak the new password into crash logs.
            throw CameraOnvifException(e.stage, sanitized.removePrefix("${e.stage}: "))
        } finally {
            camera.sdk.onDestroy()
        }
        val main = verified.info.streams[0]
        val sub = verified.info.streams.getOrNull(1)
        return OnvifRecord(
            deviceId = device.deviceId,
            address = verified.address,
            username = verified.credentials.username,
            password = verified.credentials.password,
            mainUrl = main.url,
            subUrl = sub?.url,
            mainCodec = main.codec,
            mainResolution = main.resolution,
            subCodec = sub?.codec,
            subResolution = sub?.resolution,
            verified = true
        )
    }

    /**
     * Sets the camera's ONVIF password and returns the password that is now active on the camera.
     * When [targetPassword] is null, tries the common default passwords in order (keeping `"admin"` when the camera
     * allows it, or the first fallback default such as `"admin123"` when the firmware forbids `"admin"`).
     */
    private suspend fun ensurePassword(
        camera: Camera,
        existingPassword: String?,
        targetPassword: String?,
        wasPasswordChanged: Boolean,
        attemptedPasswords: MutableSet<String>,
        savePassword: suspend (String) -> Unit
    ): String {
        val oldCandidates = (listOfNotNull(existingPassword) + Credentials.DEFAULT_PASSWORDS).distinct()
        val fallbacks = listOf("admin123", "123456", "12345678", "admin1234")
        if (targetPassword != null) {
            for (old in oldCandidates) {
                if (old == targetPassword) {
                    val hop = if (targetPassword == "admin123") generatePassword() else "admin123"
                    if (tryChangePassword(camera, old, hop, attemptedPasswords, savePassword)) {
                        if (tryChangePassword(camera, hop, targetPassword, attemptedPasswords, savePassword)) {
                            return targetPassword
                        }
                        throw CameraOnvifException(
                            "ONVIF password",
                            "the camera rejected the requested password (kept temporary password)"
                        )
                    }
                } else if (tryChangePassword(camera, old, targetPassword, attemptedPasswords, savePassword)) {
                    return targetPassword
                }
            }
            throw CameraOnvifException("ONVIF password", "the camera rejected the password change")
        }

        for (old in oldCandidates) {
            if (old == DEFAULT_PASSWORD) {
                for (hop in fallbacks + listOf(generatePassword())) {
                    if (tryChangePassword(camera, old, hop, attemptedPasswords, savePassword)) {
                        // The camera accepted `hop` (so onvif_pw_changed is now true). Try rotating back to `admin`;
                        // if this model forbids `admin` as a new password, keep `hop`.
                        return if (tryChangePassword(camera, hop, DEFAULT_PASSWORD, attemptedPasswords, savePassword)) {
                            DEFAULT_PASSWORD
                        } else {
                            hop
                        }
                    }
                }
            } else {
                if (tryChangePassword(camera, old, DEFAULT_PASSWORD, attemptedPasswords, savePassword)) {
                    return DEFAULT_PASSWORD
                }
                for (candidate in fallbacks + listOf(generatePassword())) {
                    if (candidate == old) {
                        if (!wasPasswordChanged) continue
                        val verifyHop = if (old == "admin123") "12345678" else "admin123"
                        if (tryChangePassword(camera, old, verifyHop, attemptedPasswords, savePassword)) {
                            return if (tryChangePassword(camera, verifyHop, old, attemptedPasswords, savePassword)) {
                                old
                            } else {
                                verifyHop
                            }
                        }
                    } else if (tryChangePassword(camera, old, candidate, attemptedPasswords, savePassword)) {
                        return candidate
                    }
                }
            }
        }
        throw CameraOnvifException("ONVIF password", "the camera rejected all default password combinations")
    }

    private suspend fun tryChangePassword(
        camera: Camera,
        oldPassword: String,
        password: String,
        attemptedPasswords: MutableSet<String>,
        savePassword: suspend (String) -> Unit
    ): Boolean {
        attemptedPasswords += oldPassword
        attemptedPasswords += password
        return try {
            changePassword(camera, oldPassword, password, savePassword)
            true
        } catch (e: CameraOnvifException) {
            if (e.message?.contains("res=failed") == true) false else throw e
        }
    }

    private suspend fun changePassword(
        camera: Camera,
        oldPassword: String,
        password: String,
        savePassword: suspend (String) -> Unit
    ) {
        val dp = camera.dps.changePassword
        val stage = "ONVIF password result (DP $dp)"
        val result = CompletableDeferred<Unit>()
        val listener = object : IDevListener {
            override fun onDpUpdate(devId: String, dpStr: String) {
                val raw = JSON.parseObject(dpStr)[dp] as? String ?: return
                Log.d(TAG, "DP$dp update received")
                if (raw.isBlank()) {
                    Log.d(TAG, "DP$dp update is blank, ignoring")
                    return
                }
                try {
                    val json = JSON.parseObject(raw) ?: return
                    // Our own published payload may echo back without a result; only a payload carrying "res" is the device's answer.
                    if (!json.containsKey("res")) {
                        Log.d(TAG, "DP$dp update has no res field, ignoring (echo)")
                        return
                    }
                    val res = json.getString("res")
                    Log.d(TAG, "DP$dp res=$res errcode=${json["errcode"]}")
                    if (res == "ok") {
                        result.complete(Unit)
                    } else {
                        result.completeExceptionally(
                            CameraOnvifException(stage, "res=$res errcode=${json["errcode"]}")
                        )
                    }
                } catch (e: Exception) {
                    result.completeExceptionally(
                        CameraOnvifException(stage, "unparseable DP$dp result (${e.javaClass.simpleName})")
                    )
                }
            }

            override fun onRemoved(devId: String) {
                result.completeExceptionally(CameraOnvifException(stage, "device removed"))
            }

            override fun onStatusChanged(devId: String, online: Boolean) = Unit
            override fun onNetworkStatusChanged(devId: String, status: Boolean) = Unit
            override fun onDevInfoUpdate(devId: String) = Unit
        }
        camera.sdk.registerDevListener(listener)
        try {
            // Persist before sending: a publish that errors or times out may still have been applied by the camera.
            savePassword(password)
            publish(
                camera,
                "ONVIF password (DP $dp)",
                JSON.toJSONString(
                    mapOf(
                        dp to JSON.toJSONString(mapOf("oldpwd" to oldPassword, "newpwd" to password))
                    )
                )
            )
            awaitCallback(camera, "ONVIF password read (DP $dp)") { callback -> camera.sdk.getDpList(listOf(dp), callback) }
            try {
                withTimeout(SDK_TIMEOUT_MS) { result.await() }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "DP$dp result timed out")
                throw CameraOnvifException(stage, "timed out waiting for DP$dp result with res", e)
            }
        } catch (e: CameraOnvifException) {
            if (e.message?.contains("res=failed") == true) {
                savePassword(oldPassword)
            }
            throw e
        } finally {
            camera.sdk.unRegisterDevListener()
        }
    }

    private class Camera(
        val sdk: IThingDevice,
        val deviceId: String,
        val dps: OnvifDps,
        val onProgress: (String) -> Unit
    )

    /** ONVIF data point IDs differ between camera models; their codes do not. */
    private class OnvifDps(
        val switch: String,
        val ipAddress: String,
        val passwordChanged: String,
        val changePassword: String,
        val ipType: String
    )

    private fun onvifDps(deviceId: String): OnvifDps {
        val schema = checkNotNull(ThingHomeSdk.getDataInstance().getSchema(deviceId)) {
            "Tuya SDK has no schema for $deviceId"
        }
        val idsByCode = schema.values.associate { it.code to it.id }
        fun id(code: String) = idsByCode[code] ?: throw CameraOnvifException(
            "ONVIF data points",
            "this camera has no $code data point, so Tuya cannot switch its ONVIF on " +
                "(ONVIF codes it has: ${idsByCode.keys.filter { it.startsWith("onvif") }.sorted().ifEmpty { listOf("none") }})"
        )
        return OnvifDps(
            switch = id("onvif_switch"),
            ipAddress = id("onvif_ip_addr"),
            passwordChanged = id("onvif_pw_changed"),
            changePassword = id("onvif_change_pwd"),
            ipType = id("onvif_iptype_config")
        )
    }

    private suspend fun publish(camera: Camera, stage: String, payload: String) {
        Log.d(TAG, "publish start: $stage")
        awaitCallback(camera, stage) { callback -> camera.sdk.publishDps(payload, callback) }
    }

    private fun deviceBean(camera: Camera) =
        checkNotNull(ThingHomeSdk.getDataInstance().getDeviceBean(camera.deviceId)) {
            "Tuya SDK has no device bean for ${camera.deviceId}"
        }

    private fun isOnline(camera: Camera): Boolean = deviceBean(camera).isOnline == true

    // Changing the IP mode makes the camera drop off briefly; any SDK call sent meanwhile fails with 11005.
    private suspend fun awaitOnline(camera: Camera, stage: String) {
        if (isOnline(camera)) return
        Log.d(TAG, "camera offline before: $stage")
        camera.onProgress("The camera is reconnecting to Tuya. Waiting for it to come back online…")
        // OrNull, not withTimeout: an enclosing timeout must propagate instead of being reported as this one.
        withTimeoutOrNull(ONLINE_TIMEOUT_MS) {
            while (!isOnline(camera)) delay(1000)
        } ?: throw CameraOnvifException(stage, "camera stayed offline for ${ONLINE_TIMEOUT_MS / 1000}s")
        Log.d(TAG, "camera back online before: $stage")
    }

    private suspend fun awaitCallback(camera: Camera, stage: String, start: (IResultCallback) -> Unit) {
        awaitOnline(camera, stage)
        withTimeoutOrNull(SDK_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { continuation ->
                start(object : IResultCallback {
                    // publishDps reports some failures with a null message; only the code is guaranteed.
                    override fun onError(code: String, error: String?) {
                        Log.e(TAG, "callback error: $stage code=$code")
                        continuation.resumeWithException(CameraOnvifException(stage, "code $code: $error"))
                    }

                    override fun onSuccess() {
                        Log.d(TAG, "callback success: $stage")
                        continuation.resume(Unit)
                    }
                })
            }
        } ?: throw CameraOnvifException(stage, "SDK callback timed out after ${SDK_TIMEOUT_MS / 1000}s")
    }

    private suspend fun readDp(camera: Camera, stage: String, dp: String): String {
        awaitCallback(camera, stage) { callback -> camera.sdk.getDpList(listOf(dp), callback) }
        return deviceBean(camera).dps[dp]?.toString()
            ?: throw CameraOnvifException(stage, "camera reported no value for DP $dp")
    }

    private suspend fun awaitDp(camera: Camera, stage: String, dp: String, expected: Any) {
        var last: Any? = null
        withTimeoutOrNull(STATE_TIMEOUT_MS) {
            while (true) {
                awaitCallback(camera, stage) { callback -> camera.sdk.getDpList(listOf(dp), callback) }
                last = deviceBean(camera).dps[dp]
                Log.d(TAG, "$stage: $dp=$last")
                if (last == expected) break
                delay(1000)
            }
        } ?: throw CameraOnvifException(stage, "timed out waiting for $dp=$expected; last seen $dp=$last")
    }

    private class VerifiedOnvif(val address: String, val info: OnvifInfo, val credentials: Credentials)

    // getDpList refreshes the SDK's cached DPs but fires no update for unchanged values, so read the cache.
    // After the IP-mode cycle the camera takes several seconds to bring its ONVIF and RTSP daemons up, so keep
    // polling until ONVIF discovery and the stream probe both succeed.
    private suspend fun verifyState(camera: Camera, password: String): VerifiedOnvif {
        val stage = "ONVIF state verification"
        val dps = camera.dps
        val credentials = Credentials.candidates(Credentials(DEFAULT_USERNAME, password))
        var last = "nothing read yet"
        Log.d(TAG, "verify start")
        val verified = withTimeoutOrNull(STATE_TIMEOUT_MS) {
            var found: VerifiedOnvif? = null
            while (found == null) {
                awaitCallback(camera, stage) { callback ->
                    camera.sdk.getDpList(listOf(dps.switch, dps.passwordChanged, dps.ipAddress), callback)
                }
                val values = deviceBean(camera).dps
                val switch = values[dps.switch]
                val passwordChanged = values[dps.passwordChanged]
                val ip = values[dps.ipAddress] as? String
                if (switch == true && passwordChanged == true && ip != null && HomeAssistantProtocol.isRfc1918Address(ip)) {
                    val probeResult = withContext(Dispatchers.IO) {
                        val discovered = OnvifDiscovery.discover(context)
                            .filter { it.ip == ip }
                            .flatMap { it.serviceUrls }
                            .distinct()
                        if (discovered.isEmpty()) {
                            null to "ONVIF discovery not answered yet"
                        } else {
                            val serviceUrl = discovered.first()
                            try {
                                val info = OnvifClient().probe(serviceUrl, credentials)
                                if (info.streams.isEmpty()) {
                                    null to "no stream profiles yet ($serviceUrl)"
                                } else {
                                    val activeCreds = info.credentials ?: Credentials(DEFAULT_USERNAME, password)
                                    VerifiedOnvif(ip, info, activeCreds) to "ready at $serviceUrl"
                                }
                            } catch (e: OnvifException) {
                                null to "ONVIF starting (${e.message})"
                            } catch (e: IOException) {
                                null to "streams starting (${e.javaClass.simpleName}: ${e.message})"
                            } catch (e: IllegalStateException) {
                                null to "streams starting (${e.message})"
                            }
                        }
                    }
                    last = "${dps.switch}=$switch, ${dps.passwordChanged}=$passwordChanged, ${dps.ipAddress}=$ip, " +
                        probeResult.second
                    Log.d(TAG, "verify read: $last")
                    found = probeResult.first
                } else {
                    last = "${dps.switch}=$switch, ${dps.passwordChanged}=$passwordChanged, ${dps.ipAddress}=$ip"
                    Log.d(TAG, "verify read: $last")
                }
                if (found == null) {
                    if (switch == false) publish(camera, "ONVIF switch (DP ${dps.switch})", """{"${dps.switch}":true}""")
                    delay(2000)
                }
            }
            found
        }
        return verified ?: throw CameraOnvifException(
            stage,
            "timed out waiting for ${dps.switch}=true, ${dps.passwordChanged}=true and live ONVIF/RTSP streams on " +
                "the private IPv4 in ${dps.ipAddress}; last seen: $last"
        )
    }

    private fun generatePassword(): String {
        val random = SecureRandom()
        return buildString { repeat(PASSWORD_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
    }

    companion object {
        // admin is the factory ONVIF password. A reboot keeps the current password; only a factory reset returns to this.
        const val DEFAULT_PASSWORD = "admin"

        private const val TAG = "CameraOnvif"
        const val DEFAULT_USERNAME = "admin"
        private const val PASSWORD_LENGTH = 20
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private const val SDK_TIMEOUT_MS = 15_000L
        private const val ONLINE_TIMEOUT_MS = 60_000L
        // Confirmation waits include a possible reconnect while the camera is offline.
        const val STATE_TIMEOUT_MS = ONLINE_TIMEOUT_MS + SDK_TIMEOUT_MS
    }
}
