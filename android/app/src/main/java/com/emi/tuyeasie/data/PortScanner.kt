package com.emi.tuyeasie.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

object PortScanner {
    const val SERVICE_RTSP = "RTSP"
    const val SERVICE_ONVIF = "ONVIF"
    private const val LAST_PORT = 65_535
    private const val CONNECT_TIMEOUT_MS = 500
    private const val BANNER_TIMEOUT_MS = 800
    private const val PROBE_TIMEOUT_MS = 1500
    private const val PROGRESS_STEP = 500

    /** [onProgress] is called from scanner threads. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun scan(ip: String, onProgress: (done: Int, total: Int) -> Unit): List<OpenPort> =
        withContext(Dispatchers.IO.limitedParallelism(512)) {
            coroutineScope {
                val done = AtomicInteger()
                val open = (1..LAST_PORT).map { port ->
                    async {
                        val isOpen = try {
                            Socket().use { it.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS) }
                            true
                        } catch (e: ConnectException) {
                            false
                        } catch (e: SocketTimeoutException) {
                            false
                        } catch (e: NoRouteToHostException) {
                            false
                        }
                        val count = done.incrementAndGet()
                        if (count % PROGRESS_STEP == 0 || count == LAST_PORT) onProgress(count, LAST_PORT)
                        if (isOpen) port else null
                    }
                }.awaitAll().filterNotNull()
                open.map { port -> async { identify(ip, port) } }.awaitAll()
            }
        }

    fun guessRtspStreams(ip: String, rtspPorts: List<Int>, username: String, password: String?): List<DetectedStream> {
        val streams = mutableListOf<DetectedStream>()
        for (port in rtspPorts) {
            for (path in listOf("stream0", "stream1", "live/ch00_0", "live/ch00_1", "11", "12", "h264", "onvif1", "")) {
                val url = "rtsp://$ip:$port/$path"
                // A refused, reset or non-200 DESCRIBE is the expected answer for a path the camera does not serve.
                val info = try {
                    RtspClient.describe(url, username, password)
                } catch (e: RtspStatusException) {
                    continue
                } catch (e: IOException) {
                    continue
                }
                streams.add(DetectedStream(path.ifEmpty { "root" }, url, info.codec, info.resolution, StreamSource.PORT_SCAN))
            }
        }
        return streams
    }

    private class Known(val label: String, val description: String)

    private val knownPorts = mapOf(
        21 to Known("FTP", "File transfer"),
        22 to Known("SSH", "Secure shell"),
        23 to Known("Telnet", "Unencrypted remote shell"),
        25 to Known("SMTP", "Mail transfer"),
        53 to Known("DNS", "Domain name service"),
        80 to Known("HTTP", "Web interface"),
        81 to Known("HTTP", "Alternate web interface, common on cameras"),
        88 to Known("HTTP", "Alternate web interface on some cameras (Kerberos elsewhere)"),
        443 to Known("HTTPS", "Secure web interface"),
        502 to Known("Modbus", "Industrial control protocol"),
        554 to Known("RTSP", "Standard video streaming port"),
        1883 to Known("MQTT", "IoT message broker"),
        1935 to Known("RTMP", "Live video streaming"),
        2000 to Known("Vendor", "Vendor control port on some cameras (Cisco SCCP elsewhere)"),
        3000 to Known("HTTP", "Web interface or development server"),
        5000 to Known("ONVIF/HTTP", "ONVIF/HTTP on some cameras"),
        5353 to Known("mDNS", "Multicast DNS service discovery"),
        6554 to Known("RTSP", "Video streaming on some cameras"),
        6668 to Known("Tuya", "Tuya local protocol (what Tuya Local in Home Assistant uses)"),
        6669 to Known("Tuya", "Tuya local protocol, secondary port"),
        7000 to Known("Vendor", "Vendor media/control port on some cameras"),
        8000 to Known("HTTP/ONVIF", "Web interface or ONVIF on some cameras"),
        8080 to Known("HTTP/ONVIF", "Web interface or ONVIF on some cameras"),
        8081 to Known("HTTP", "Alternate web interface"),
        8443 to Known("HTTPS", "Alternate secure web interface"),
        8554 to Known("RTSP", "Alternate video streaming port"),
        8883 to Known("MQTT/TLS", "MQTT over TLS"),
        8899 to Known("ONVIF", "ONVIF on some cameras"),
        9000 to Known("Vendor", "Hikvision SDK/various"),
        9527 to Known("Vendor", "Debug/control console on some cameras"),
        10554 to Known("RTSP", "Alternate video streaming port"),
        34567 to Known("XMEye", "XMEye/NetSurveillance (DVR/camera protocol)"),
        37777 to Known("Dahua", "Dahua camera/NVR control protocol"),
        49152 to Known("UPnP", "UPnP device description and control")
    )

    private fun identify(ip: String, port: Int): OpenPort {
        val known = knownPorts[port]
        val description = if (known != null) known.description else "Not a well-known port"
        // Only a probe confirms a protocol; the port number alone is a hint.
        val fallbackService = if (known != null) "${known.label} (unconfirmed)" else "Unknown"

        val banner = readBanner(ip, port)
        if (banner != null) {
            val service = when {
                banner.startsWith("SSH-") -> "SSH"
                banner.startsWith("220") && banner.contains("SMTP", ignoreCase = true) -> "SMTP"
                banner.startsWith("220") -> "FTP"
                banner == TELNET_NEGOTIATION -> "Telnet"
                else -> null
            }
            if (service != null) return OpenPort(port, service, description, banner)
        }
        val rtsp = RtspClient.options(ip, port, PROBE_TIMEOUT_MS)
        if (rtsp != null) {
            val detail = listOfNotNull(
                rtsp.headers["server"]?.let { "Server: ${it.first()}" },
                rtsp.headers["public"]?.let { "Public: ${it.first()}" }
            ).joinToString("; ")
            return OpenPort(port, SERVICE_RTSP, description, detail.ifEmpty { null })
        }
        val http = probeHttp(ip, port)
        val onvif = probeOnvif(ip, port)
        if (onvif != null) {
            return OpenPort(port, SERVICE_ONVIF, description, listOfNotNull(onvif, http).joinToString("; "))
        }
        if (http != null) return OpenPort(port, "HTTP", description, http)
        if (probeTls(ip, port)) return OpenPort(port, "TLS", description, null)
        return OpenPort(port, fallbackService, description, banner)
    }

    private const val TELNET_NEGOTIATION = "Telnet option negotiation"

    // Only services that talk first (SSH, FTP, SMTP, telnet) answer here; silence is the common outcome.
    private fun readBanner(ip: String, port: Int): String? {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), PROBE_TIMEOUT_MS)
                socket.soTimeout = BANNER_TIMEOUT_MS
                val buffer = ByteArray(256)
                val length = socket.getInputStream().read(buffer)
                if (length <= 0) return null
                if (buffer[0] == 0xFF.toByte()) return TELNET_NEGOTIATION
                return String(buffer, 0, length, Charsets.ISO_8859_1)
                    .lineSequence()
                    .map { it.trim() }
                    .firstOrNull { line -> line.isNotEmpty() && line.all { it in ' '..'~' } }
            }
        } catch (e: SocketTimeoutException) {
            return null
        } catch (e: SocketException) {
            return null
        }
    }

    private class HttpReply(val statusLine: String, val headers: Map<String, List<String>>, val body: String)

    private fun probeHttp(ip: String, port: Int): String? {
        val reply = exchange(
            ip, port,
            "GET / HTTP/1.0\r\nHost: $ip:$port\r\nUser-Agent: tuyeasie\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)
        ) ?: return null
        val title = Regex("<title[^>]*>([^<]*)", RegexOption.IGNORE_CASE).find(reply.body)?.groupValues?.get(1)?.trim()
        return listOfNotNull(
            reply.statusLine,
            reply.headers["server"]?.let { "Server: ${it.first()}" },
            title?.takeIf { it.isNotEmpty() }?.let { "Title: $it" }
        ).joinToString("; ")
    }

    private fun probeOnvif(ip: String, port: Int): String? {
        val soap = (
            """<?xml version="1.0" encoding="UTF-8"?>""" +
                """<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"><s:Body>""" +
                """<tds:GetSystemDateAndTime xmlns:tds="http://www.onvif.org/ver10/device/wsdl"/>""" +
                """</s:Body></s:Envelope>"""
            ).toByteArray(Charsets.UTF_8)
        val head = "POST /onvif/device_service HTTP/1.0\r\nHost: $ip:$port\r\nUser-Agent: tuyeasie\r\n" +
            "Content-Type: application/soap+xml; charset=utf-8\r\nContent-Length: ${soap.size}\r\nConnection: close\r\n\r\n"
        val reply = exchange(ip, port, head.toByteArray(Charsets.US_ASCII) + soap) ?: return null
        if (!reply.body.contains("Envelope")) return null
        return if (reply.body.contains("NotAuthorized")) "ONVIF login required" else "ONVIF answered without login"
    }

    /** Null when the peer did not answer with an HTTP response (closed, reset, silent or another protocol). */
    private fun exchange(ip: String, port: Int, request: ByteArray): HttpReply? {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), PROBE_TIMEOUT_MS)
                socket.soTimeout = PROBE_TIMEOUT_MS
                socket.getOutputStream().apply {
                    write(request)
                    flush()
                }
                val input = socket.getInputStream()
                val prefix = readUpTo(input, 5)
                if (String(prefix, Charsets.ISO_8859_1) != "HTTP/") return null
                val raw = prefix + readUpTo(input, 16 * 1024)
                val text = String(raw, Charsets.ISO_8859_1)
                val headerEnd = text.indexOf("\r\n\r\n")
                val headLines = (if (headerEnd < 0) text else text.substring(0, headerEnd)).split("\r\n")
                val headers = mutableMapOf<String, MutableList<String>>()
                for (line in headLines.drop(1)) {
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        headers.getOrPut(line.substring(0, separator).trim().lowercase()) { mutableListOf() }
                            .add(line.substring(separator + 1).trim())
                    }
                }
                val body = if (headerEnd < 0) "" else String(raw, headerEnd + 4, raw.size - headerEnd - 4, Charsets.UTF_8)
                return HttpReply(headLines.first(), headers, body)
            }
        } catch (e: SocketTimeoutException) {
            return null
        } catch (e: SocketException) {
            return null
        }
    }

    /** Reads until [limit] bytes, end of stream, or the read timeout once something has arrived. */
    private fun readUpTo(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (out.size() < limit) {
            val n = try {
                input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            } catch (e: SocketTimeoutException) {
                // Keep-alive servers leave the connection open after the reply; what arrived is the answer.
                if (out.size() == 0) throw e
                break
            }
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun probeTls(ip: String, port: Int): Boolean {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), PROBE_TIMEOUT_MS)
                socket.soTimeout = PROBE_TIMEOUT_MS
                socket.getOutputStream().apply {
                    write(TLS_CLIENT_HELLO)
                    flush()
                }
                val reply = readUpTo(socket.getInputStream(), 2)
                return reply.size == 2 && reply[0] == 0x16.toByte() && reply[1] == 0x03.toByte()
            }
        } catch (e: SocketTimeoutException) {
            return false
        } catch (e: SocketException) {
            return false
        }
    }

    // TLS 1.2 ClientHello offering common RSA and ECDHE suites with no extensions; enough for a ServerHello from
    // typical embedded TLS stacks.
    private val TLS_CLIENT_HELLO: ByteArray = run {
        val cipherSuites = byteArrayOf(
            0xC0.toByte(), 0x2F, 0xC0.toByte(), 0x30, 0xC0.toByte(), 0x2B, 0xC0.toByte(), 0x2C,
            0xC0.toByte(), 0x13, 0xC0.toByte(), 0x14, 0x00, 0x9C.toByte(), 0x00, 0x9D.toByte(),
            0x00, 0x2F, 0x00, 0x35
        )
        val body = byteArrayOf(0x03, 0x03) +
            ByteArray(32).also { SecureRandom().nextBytes(it) } +
            byteArrayOf(0x00) +
            byteArrayOf(0x00, cipherSuites.size.toByte()) + cipherSuites +
            byteArrayOf(0x01, 0x00)
        val handshake = byteArrayOf(0x01, 0x00, (body.size ushr 8).toByte(), body.size.toByte()) + body
        byteArrayOf(0x16, 0x03, 0x01, (handshake.size ushr 8).toByte(), handshake.size.toByte()) + handshake
    }
}
