package com.emi.tuyeasie.data

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** The server answered DESCRIBE with a non-200 status (or 401 while no password was given). */
class RtspStatusException(val status: Int, message: String) : IllegalStateException(message)

object RtspClient {
    private const val TIMEOUT_MS = 10_000
    private const val RTSP_PREFIX = "RTSP/"

    data class StreamInfo(val codec: String?, val resolution: String?)

    class RtspResponse(
        val status: Int,
        val reason: String,
        val headers: Map<String, List<String>>,
        val body: String
    )

    fun describe(url: String, username: String, password: String?): StreamInfo {
        fun fail(reason: String): Nothing = throw IllegalStateException(reason)

        var response = rtspDescribe(url, null)
        if (response.status == 401) {
            if (password == null) throw RtspStatusException(401, "RTSP credentials are required")
            val challenges = response.headers["www-authenticate"].orEmpty()
            val digest = challenges.firstOrNull { it.startsWith("Digest", ignoreCase = true) }
            val basic = challenges.firstOrNull { it.startsWith("Basic", ignoreCase = true) }
            val authorization = when {
                digest != null -> digestAuthorization(digest, url, username, password)
                basic != null -> "Basic " + Base64.getEncoder()
                    .encodeToString("$username:$password".toByteArray())
                else -> fail("401 without a Basic or Digest challenge")
            }
            response = rtspDescribe(url, authorization)
        }
        if (response.status != 200) {
            throw RtspStatusException(response.status, "DESCRIBE returned ${response.status} ${response.reason}")
        }
        return parseSdp(response.body) ?: fail("SDP has no video media section")
    }

    /** Probe answer: null means the peer closed, stayed silent or replied with something that is not RTSP. */
    fun options(host: String, port: Int, timeoutMs: Int): RtspResponse? {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                socket.getOutputStream().apply {
                    write("OPTIONS rtsp://$host:$port RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: tuyeasie\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    flush()
                }
                val input = socket.getInputStream()
                val prefix = ByteArray(RTSP_PREFIX.length)
                var read = 0
                while (read < prefix.size) {
                    val n = input.read(prefix, read, prefix.size - read)
                    if (n < 0) return null
                    read += n
                }
                if (String(prefix, Charsets.ISO_8859_1) != RTSP_PREFIX) return null
                return readResponse(input, prefix)
            }
        } catch (e: SocketTimeoutException) {
            return null
        } catch (e: SocketException) {
            // Non-RTSP services commonly reset the connection on an unexpected request.
            return null
        }
    }

    private fun rtspDescribe(url: String, authorization: String?): RtspResponse {
        val uri = URI(url)
        val port = if (uri.port < 0) 554 else uri.port
        Socket().use { socket ->
            socket.connect(InetSocketAddress(uri.host, port), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
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

    private fun readResponse(input: InputStream, consumed: ByteArray = ByteArray(0)): RtspResponse {
        val head = ByteArrayOutputStream()
        head.write(consumed)
        var tail = 0
        while (tail != 0x0d0a0d0a) {
            val b = input.read()
            if (b < 0) throw EOFException("connection closed before RTSP headers ended")
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
            if (n < 0) throw EOFException("connection closed inside RTSP body")
            read += n
        }
        return RtspResponse(
            statusLine[1].toInt(),
            statusLine.getOrElse(2) { "" },
            headers,
            String(body, Charsets.UTF_8)
        )
    }

    private fun digestAuthorization(challenge: String, url: String, username: String, password: String): String {
        val params = Regex("""(\w+)=(?:"([^"]*)"|([^,\s]+))""")
            .findAll(challenge.substring("Digest".length))
            .associate { it.groupValues[1].lowercase() to (it.groups[2]?.value ?: it.groupValues[3]) }
        val realm = checkNotNull(params["realm"]) { "Digest challenge has no realm" }
        val nonce = checkNotNull(params["nonce"]) { "Digest challenge has no nonce" }
        val qop = params["qop"]?.split(",")?.map { it.trim() }?.firstOrNull { it == "auth" }
        check(params["qop"] == null || qop != null) { "Digest qop ${params["qop"]} does not offer auth" }
        val ha1 = md5("$username:$realm:$password")
        val ha2 = md5("DESCRIBE:$url")
        val header = StringBuilder("Digest username=\"$username\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$url\"")
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

    private fun generateToken(length: Int): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = SecureRandom()
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }
}
