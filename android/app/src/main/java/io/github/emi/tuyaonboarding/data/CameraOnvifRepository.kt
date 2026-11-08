package io.github.emi.tuyaonboarding.data

import android.util.Log
import com.alibaba.fastjson.JSON
import com.thingclips.smart.home.sdk.ThingHomeSdk
import com.thingclips.smart.sdk.api.IDevListener
import com.thingclips.smart.sdk.api.IResultCallback
import com.thingclips.smart.sdk.api.IThingDevice
import io.github.emi.tuyaonboarding.hass.HomeAssistantProtocol
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
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

class CameraOnvifRepository {
    suspend fun configure(
        device: ActivatedDevice,
        existingPassword: String?,
        onProgress: (String) -> Unit,
        savePassword: suspend (String) -> Unit
    ): OnvifRecord {
        val oldPassword = existingPassword ?: DEFAULT_PASSWORD
        // Always rotate: the camera refuses a change whose new password equals the current one (DP 238 errcode 2).
        val password = generatePassword()
        val camera = Camera(ThingHomeSdk.newDeviceInstance(device.deviceId), device.deviceId, onProgress)
        val address: String
        try {
            // The camera rejects a password change once ONVIF is already on, so set the password first.
            onProgress("Setting the ONVIF password…")
            changePassword(camera, oldPassword, password, savePassword)
            Log.d(TAG, "DP238 confirmed res=ok, turning ONVIF on")
            onProgress("Turning ONVIF on…")
            publish(camera, "ONVIF switch (DP 237)", """{"237":true}""")
            awaitDp(camera, "ONVIF switch on confirmation (DP 237)", "237", true)
            // The camera only (re)starts its ONVIF and RTSP servers when the IP mode actually changes, so flip it to
            // the other mode and back. Forcing dynamic then static does nothing on the first step when the camera is
            // already dynamic, and it would leave a dynamic camera on static.
            val mode = readDp(camera, "ONVIF IP mode read (DP 241)", "241")
            if (mode != "0" && mode != "1") {
                throw CameraOnvifException("ONVIF IP mode read (DP 241)", "camera reported IP mode '$mode', expected 0 or 1")
            }
            val flipped = if (mode == "0") "1" else "0"
            onProgress("Toggling the camera's IP mode to start ONVIF…")
            publish(camera, "ONVIF IP mode toggle (DP 241)", """{"241":"$flipped"}""")
            awaitDp(camera, "ONVIF IP mode toggle confirmation (DP 241)", "241", flipped)
            delay(1500)
            onProgress("Restoring the camera's IP mode…")
            publish(camera, "ONVIF IP mode restore (DP 241)", """{"241":"$mode"}""")
            awaitDp(camera, "ONVIF IP mode restore confirmation (DP 241)", "241", mode)
            delay(1500)
            onProgress("Waiting for the camera to report its ONVIF address…")
            address = verifyState(camera)
        } catch (e: CameraOnvifException) {
            val message = checkNotNull(e.message)
            val sanitized = message.replace(password, "[redacted]")
            if (sanitized == message) throw e
            // The cause is omitted: it carries the unredacted message and would leak the new password into crash logs.
            throw CameraOnvifException(e.stage, sanitized.removePrefix("${e.stage}: "))
        } finally {
            camera.sdk.onDestroy()
        }
        onProgress("Checking both RTSP streams at $address…")
        val mainUrl = "rtsp://$address:8554/stream0"
        val subUrl = "rtsp://$address:8554/stream1"
        val (mainInfo, subInfo) = withContext(Dispatchers.IO) {
            describe(address, mainUrl, password, "stream0") to describe(address, subUrl, password, "stream1")
        }
        return OnvifRecord(
            deviceId = device.deviceId,
            address = address,
            password = password,
            mainUrl = mainUrl,
            subUrl = subUrl,
            mainCodec = mainInfo.codec,
            mainResolution = mainInfo.resolution,
            subCodec = subInfo.codec,
            subResolution = subInfo.resolution,
            verified = true
        )
    }

    // A null password means no ONVIF password was ever sent to this camera, so it still has the factory default.
    fun unverifiedRecord(device: ActivatedDevice, address: String, knownPassword: String?) = OnvifRecord(
        deviceId = device.deviceId,
        address = address,
        password = knownPassword ?: DEFAULT_PASSWORD,
        mainUrl = "rtsp://$address:$RTSP_PORT/stream0",
        subUrl = "rtsp://$address:$RTSP_PORT/stream1",
        verified = false
    )

    private suspend fun changePassword(
        camera: Camera,
        oldPassword: String,
        password: String,
        savePassword: suspend (String) -> Unit
    ) {
        val stage = "ONVIF password result (DP 238)"
        val result = CompletableDeferred<Unit>()
        val listener = object : IDevListener {
            override fun onDpUpdate(devId: String, dpStr: String) {
                val raw = JSON.parseObject(dpStr)["238"] as? String ?: return
                Log.d(TAG, "DP238 update received")
                if (raw.isBlank()) {
                    Log.d(TAG, "DP238 update is blank, ignoring")
                    return
                }
                try {
                    val json = JSON.parseObject(raw) ?: return
                    // Our own published payload may echo back without a result; only a payload carrying "res" is the device's answer.
                    if (!json.containsKey("res")) {
                        Log.d(TAG, "DP238 update has no res field, ignoring (echo)")
                        return
                    }
                    val res = json.getString("res")
                    Log.d(TAG, "DP238 res=$res errcode=${json["errcode"]}")
                    if (res == "ok") {
                        result.complete(Unit)
                    } else {
                        result.completeExceptionally(
                            CameraOnvifException(stage, "res=$res errcode=${json["errcode"]}")
                        )
                    }
                } catch (e: Exception) {
                    result.completeExceptionally(
                        CameraOnvifException(stage, "unparseable DP238 result (${e.javaClass.simpleName})")
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
                "ONVIF password (DP 238)",
                JSON.toJSONString(
                    mapOf(
                        "238" to JSON.toJSONString(mapOf("oldpwd" to oldPassword, "newpwd" to password))
                    )
                )
            )
            awaitCallback(camera, "ONVIF password read (DP 238)") { callback -> camera.sdk.getDpList(listOf("238"), callback) }
            try {
                withTimeout(SDK_TIMEOUT_MS) { result.await() }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "DP238 result timed out")
                throw CameraOnvifException(stage, "timed out waiting for DP238 result with res", e)
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

    private class Camera(val sdk: IThingDevice, val deviceId: String, val onProgress: (String) -> Unit)

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
                    override fun onError(code: String, error: String) {
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

    // getDpList refreshes the SDK's cached DPs but fires no update for unchanged values, so read the cache.
    // The IP-mode cycle can move the camera to a new DHCP address, so DP 240 counts only once RTSP answers there.
    private suspend fun verifyState(camera: Camera): String {
        val stage = "ONVIF state verification"
        var last = "nothing read yet"
        Log.d(TAG, "verify start")
        val address = withTimeoutOrNull(STATE_TIMEOUT_MS) {
            var found: String? = null
            while (found == null) {
                awaitCallback(camera, stage) { callback ->
                    camera.sdk.getDpList(listOf("237", "239", "240"), callback)
                }
                val dps = deviceBean(camera).dps
                val ip = dps["240"] as? String
                val reachable = ip != null && HomeAssistantProtocol.isRfc1918Address(ip) && rtspReachable(ip)
                last = "237=${dps["237"]}, 239=${dps["239"]}, 240=$ip, RTSP reachable=$reachable"
                Log.d(TAG, "verify read: $last")
                if (dps["237"] == true && dps["239"] == true && reachable) {
                    found = ip
                } else {
                    if (dps["237"] == false) publish(camera, "ONVIF switch (DP 237)", """{"237":true}""")
                    delay(2000)
                }
            }
            found
        }
        return address ?: throw CameraOnvifException(
            stage,
            "timed out waiting for 237=true, 239=true and RTSP on the private IPv4 in 240; last seen: $last"
        )
    }

    private suspend fun rtspReachable(address: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { it.connect(InetSocketAddress(address, RTSP_PORT), 2000) }
            true
        } catch (e: java.io.IOException) {
            false
        }
    }

    private fun describe(address: String, url: String, password: String, path: String): StreamInfo {
        val stage = "RTSP probe $path"
        try {
            return describeUnwrapped(address, url, password, path)
        } catch (e: CameraOnvifException) {
            throw e
        } catch (e: java.io.IOException) {
            throw CameraOnvifException(stage, "${e.javaClass.simpleName}: ${e.message} (rtsp://$address:$RTSP_PORT/$path)", e)
        } catch (e: IllegalStateException) {
            throw CameraOnvifException(stage, "${e.message} (rtsp://$address:$RTSP_PORT/$path)", e)
        } catch (e: NumberFormatException) {
            throw CameraOnvifException(stage, "malformed number in response: ${e.message} (rtsp://$address:$RTSP_PORT/$path)", e)
        }
    }

    private fun describeUnwrapped(address: String, url: String, password: String, path: String): StreamInfo {
        fun fail(reason: String): Nothing =
            throw CameraOnvifException("RTSP probe $path", "$reason (rtsp://$address:$RTSP_PORT/$path)")

        var response = rtspDescribe(address, url, null)
        if (response.status == 401) {
            val challenges = response.headers["www-authenticate"].orEmpty()
            val digest = challenges.firstOrNull { it.startsWith("Digest", ignoreCase = true) }
            val basic = challenges.firstOrNull { it.startsWith("Basic", ignoreCase = true) }
            val authorization = when {
                digest != null -> digestAuthorization(digest, url, password)
                basic != null -> "Basic " + java.util.Base64.getEncoder()
                    .encodeToString("$USERNAME:$password".toByteArray())
                else -> fail("401 without a Basic or Digest challenge")
            }
            response = rtspDescribe(address, url, authorization)
        }
        if (response.status != 200) fail("DESCRIBE returned ${response.status} ${response.reason}")
        return parseSdp(response.body) ?: fail("SDP has no video media section")
    }

    private class RtspResponse(
        val status: Int,
        val reason: String,
        val headers: Map<String, List<String>>,
        val body: String
    )

    private class StreamInfo(val codec: String?, val resolution: String?)

    private fun rtspDescribe(address: String, url: String, authorization: String?): RtspResponse {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, RTSP_PORT), RTSP_TIMEOUT_MS)
            socket.soTimeout = RTSP_TIMEOUT_MS
            val request = buildString {
                append("DESCRIBE $url RTSP/1.0\r\n")
                append("CSeq: 1\r\n")
                append("Accept: application/sdp\r\n")
                append("User-Agent: tuyeasie\r\n")
                if (authorization != null) append("Authorization: $authorization\r\n")
                append("\r\n")
            }
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.US_ASCII))
                flush()
            }
            return readResponse(socket.getInputStream())
        }
    }

    private fun readResponse(input: InputStream): RtspResponse {
        val head = ByteArrayOutputStream()
        var tail = 0
        while (tail != 0x0d0a0d0a) {
            val b = input.read()
            if (b < 0) throw java.io.EOFException("connection closed before RTSP headers ended")
            head.write(b)
            tail = (tail shl 8) or b
        }
        val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n").filter { it.isNotEmpty() }
        val statusLine = lines.first().split(" ", limit = 3)
        check(statusLine.size >= 2 && statusLine[0].startsWith("RTSP/")) { "Malformed RTSP status line: ${lines.first()}" }
        val headers = mutableMapOf<String, MutableList<String>>()
        for (line in lines.drop(1)) {
            val separator = line.indexOf(':')
            check(separator > 0) { "Malformed RTSP header: $line" }
            headers.getOrPut(line.substring(0, separator).trim().lowercase()) { mutableListOf() }
                .add(line.substring(separator + 1).trim())
        }
        val length = headers["content-length"]?.first()?.toInt() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw java.io.EOFException("connection closed inside RTSP body")
            read += n
        }
        return RtspResponse(
            statusLine[1].toInt(),
            statusLine.getOrElse(2) { "" },
            headers,
            String(body, Charsets.UTF_8)
        )
    }

    private fun digestAuthorization(challenge: String, url: String, password: String): String {
        val params = Regex("""(\w+)=(?:"([^"]*)"|([^,\s]+))""")
            .findAll(challenge.substring("Digest".length))
            .associate { it.groupValues[1].lowercase() to (it.groups[2]?.value ?: it.groupValues[3]) }
        val realm = checkNotNull(params["realm"]) { "Digest challenge has no realm" }
        val nonce = checkNotNull(params["nonce"]) { "Digest challenge has no nonce" }
        val qop = params["qop"]?.split(",")?.map { it.trim() }?.firstOrNull { it == "auth" }
        check(params["qop"] == null || qop != null) { "Digest qop ${params["qop"]} does not offer auth" }
        val ha1 = md5("$USERNAME:$realm:$password")
        val ha2 = md5("DESCRIBE:$url")
        val header = StringBuilder("Digest username=\"$USERNAME\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$url\"")
        val response = if (qop != null) {
            val nc = "00000001"
            val cnonce = generateToken(16)
            header.append(", qop=auth, nc=$nc, cnonce=\"$cnonce\"")
            md5("$ha1:$nonce:$nc:$cnonce:auth:$ha2")
        } else {
            md5("$ha1:$nonce:$ha2")
        }
        header.append(", response=\"$response\"")
        params["opaque"]?.let { header.append(", opaque=\"$it\"") }
        return header.toString()
    }

    private fun parseSdp(sdp: String): StreamInfo? {
        val lines = sdp.lines().map { it.trim() }
        val start = lines.indexOfFirst { it.startsWith("m=video") }
        if (start < 0) return null
        val section = lines.drop(start + 1).takeWhile { !it.startsWith("m=") }
        val codec = section.firstNotNullOfOrNull {
            Regex("""^a=rtpmap:\d+\s+([A-Za-z0-9]+)/""").find(it)?.groupValues?.get(1)
        }?.uppercase()?.let {
            when (it) {
                "H265", "HEVC" -> "H265"
                "H264" -> "H264"
                else -> it
            }
        }
        val resolution = section.firstNotNullOfOrNull {
            Regex("""^a=x-dimensions:\s*(\d+)\s*,\s*(\d+)""").find(it)?.let { m -> "${m.groupValues[1]}x${m.groupValues[2]}" }
                ?: Regex("""^a=framesize:\d+\s+(\d+)-(\d+)""").find(it)?.let { m -> "${m.groupValues[1]}x${m.groupValues[2]}" }
        }
        return StreamInfo(codec, resolution)
    }

    private fun md5(value: String) =
        MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.ISO_8859_1))
            .joinToString("") { "%02x".format(it) }

    private fun generatePassword() = generateToken(PASSWORD_LENGTH)

    private fun generateToken(length: Int): String {
        val random = SecureRandom()
        return buildString { repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
    }

    companion object {
        // admin is the factory ONVIF password. A reboot keeps the current password; only a factory reset returns to this.
        const val DEFAULT_PASSWORD = "admin"

        private const val TAG = "CameraOnvif"
        const val USERNAME = "admin"
        const val PASSWORD_LENGTH = 20
        const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        const val SDK_TIMEOUT_MS = 15_000L
        const val ONLINE_TIMEOUT_MS = 60_000L
        // Confirmation waits include a possible reconnect while the camera is offline.
        const val STATE_TIMEOUT_MS = ONLINE_TIMEOUT_MS + SDK_TIMEOUT_MS
        const val RTSP_PORT = 8554
        const val RTSP_TIMEOUT_MS = 10_000
    }
}
