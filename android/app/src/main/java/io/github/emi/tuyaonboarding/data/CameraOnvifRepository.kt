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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CameraOnvifException(val stage: String, message: String, cause: Throwable? = null) :
    Exception("$stage: $message", cause)

class CameraOnvifRepository {
    suspend fun configure(
        device: ActivatedDevice,
        existingPassword: String?,
        savePassword: suspend (String) -> Unit
    ): OnvifRecord {
        val password = existingPassword ?: generatePassword()
        val oldPassword = existingPassword ?: DEFAULT_PASSWORD
        val sdkDevice = ThingHomeSdk.newDeviceInstance(device.deviceId)
        val address: String
        try {
            publish(sdkDevice, "ONVIF static IP (DP 241)", """{"241":"1"}""")
            // Turn ONVIF off and back on first with a settle delay so the camera starts its ONVIF service cleanly.
            publish(sdkDevice, "ONVIF switch off (DP 237)", """{"237":false}""")
            delay(1500)
            publish(sdkDevice, "ONVIF switch (DP 237)", """{"237":true}""")
            delay(1500)
            changePassword(sdkDevice, oldPassword, password, savePassword)
            Log.d(TAG, "DP238 confirmed res=ok, verifying ONVIF state")
            address = verifyState(sdkDevice)
        } catch (e: CameraOnvifException) {
            if (existingPassword != null) throw e
            val message = checkNotNull(e.message)
            val sanitized = message.replace(password, "[redacted]")
            if (sanitized == message) throw e
            // The cause is omitted: it carries the unredacted message and would leak the new password into crash logs.
            throw CameraOnvifException(e.stage, sanitized.removePrefix("${e.stage}: "))
        } finally {
            sdkDevice.onDestroy()
        }
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
            subResolution = subInfo.resolution
        )
    }

    private suspend fun changePassword(
        sdkDevice: IThingDevice,
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
        sdkDevice.registerDevListener(listener)
        try {
            // Persist before sending: a publish that errors or times out may still have been applied by the camera.
            savePassword(password)
            publish(
                sdkDevice,
                "ONVIF password (DP 238)",
                JSON.toJSONString(
                    mapOf(
                        "238" to JSON.toJSONString(mapOf("oldpwd" to oldPassword, "newpwd" to password))
                    )
                )
            )
            awaitCallback("ONVIF password read (DP 238)") { callback -> sdkDevice.getDpList(listOf("238"), callback) }
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
            sdkDevice.unRegisterDevListener()
        }
    }

    private suspend fun publish(sdkDevice: IThingDevice, stage: String, payload: String) {
        Log.d(TAG, "publish start: $stage")
        awaitCallback(stage) { callback -> sdkDevice.publishDps(payload, callback) }
    }

    private suspend fun awaitCallback(stage: String, start: (IResultCallback) -> Unit) {
        try {
            withTimeout(SDK_TIMEOUT_MS) {
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
            }
        } catch (e: TimeoutCancellationException) {
            throw CameraOnvifException(stage, "SDK callback timed out after ${SDK_TIMEOUT_MS / 1000}s", e)
        }
    }

    private suspend fun verifyState(sdkDevice: IThingDevice): String {
        val stage = "ONVIF state verification"
        val result = CompletableDeferred<String>()
        val seen = mutableMapOf<String, Any?>()
        val listener = object : IDevListener {
            override fun onDpUpdate(devId: String, dpStr: String) {
                seen.putAll(JSON.parseObject(dpStr))
                Log.d(TAG, "verify update: 237=${seen["237"]} 239=${seen["239"]} 240=${seen["240"]}")
                if (seen["237"] == true && seen["239"] == true) {
                    val ip = seen["240"] as? String
                    if (ip != null && HomeAssistantProtocol.isRfc1918Address(ip)) result.complete(ip)
                }
            }

            override fun onRemoved(devId: String) {
                result.completeExceptionally(CameraOnvifException(stage, "device removed"))
            }

            override fun onStatusChanged(devId: String, online: Boolean) = Unit
            override fun onNetworkStatusChanged(devId: String, status: Boolean) = Unit
            override fun onDevInfoUpdate(devId: String) = Unit
        }
        sdkDevice.registerDevListener(listener)
        try {
            Log.d(TAG, "verify start")
            try {
                return withTimeout(SDK_TIMEOUT_MS) {
                    val poller = launchPoll(sdkDevice, stage, seen)
                    try {
                        result.await()
                    } finally {
                        poller.cancel()
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "verify timed out: 237=${seen["237"]} 239=${seen["239"]} 240=${seen["240"]}")
                throw CameraOnvifException(
                    stage,
                    "timed out waiting for 237=true, 239=true and private IPv4 in 240; last seen: " +
                        "237=${seen["237"]}, 239=${seen["239"]}, 240=${seen["240"]}",
                    e
                )
            }
        } finally {
            sdkDevice.unRegisterDevListener()
        }
    }

    private fun CoroutineScope.launchPoll(
        sdkDevice: IThingDevice,
        stage: String,
        seen: Map<String, Any?>
    ) = launch {
        while (true) {
            if (seen["237"] == false) {
                publish(sdkDevice, "ONVIF switch (DP 237)", """{"237":true}""")
            }
            awaitCallback(stage) { callback -> sdkDevice.getDpList(listOf("237", "239", "240"), callback) }
            delay(2000)
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

    private companion object {
        const val TAG = "CameraOnvif"
        const val DEFAULT_PASSWORD = "admin"
        const val USERNAME = "admin"
        const val PASSWORD_LENGTH = 20
        const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        const val SDK_TIMEOUT_MS = 15_000L
        const val RTSP_PORT = 8554
        const val RTSP_TIMEOUT_MS = 10_000
    }
}
