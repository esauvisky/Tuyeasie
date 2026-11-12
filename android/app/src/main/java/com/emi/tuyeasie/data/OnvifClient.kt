package com.emi.tuyeasie.data

import android.util.Xml
import java.io.StringReader
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser

class OnvifException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class OnvifClient(private val http: OkHttpClient = OkHttpClient()) {
    private var authRequired = false
    private var clockOffsetMs = 0L

    fun probe(serviceUrl: String, username: String, password: String?): OnvifInfo {
        readClockOffset(serviceUrl)
        val info = call(
            "GetDeviceInformation", serviceUrl, username, password,
            """<tds:GetDeviceInformation xmlns:tds="$DEVICE_NS"/>"""
        )
        val mac = macAddress(serviceUrl, username, password)
        val mediaUrl = mediaServiceUrl(serviceUrl, username, password)
        val profiles = call(
            "GetProfiles", mediaUrl, username, password,
            """<trt:GetProfiles xmlns:trt="$MEDIA_NS"/>"""
        ).findAll("Profiles")
        val streams = profiles.map { profile ->
            val token = checkNotNull(profile.attributes["token"]) { "GetProfiles returned a profile without a token" }
            val name = checkNotNull(profile.child("Name")) { "GetProfiles profile $token has no Name" }.value
            val uri = checkNotNull(
                call(
                    "GetStreamUri", mediaUrl, username, password,
                    """<trt:GetStreamUri xmlns:trt="$MEDIA_NS" xmlns:tt="$SCHEMA_NS">""" +
                        """<trt:StreamSetup><tt:Stream>RTP-Unicast</tt:Stream>""" +
                        """<tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></trt:StreamSetup>""" +
                        """<trt:ProfileToken>$token</trt:ProfileToken></trt:GetStreamUri>"""
                ).find("Uri")
            ) { "GetStreamUri returned no Uri for profile $token" }.value
            val stream = RtspClient.describe(uri, username, password)
            DetectedStream(name, uri, stream.codec, stream.resolution, StreamSource.ONVIF)
        }
        return OnvifInfo(
            serviceUrl = serviceUrl,
            manufacturer = info.find("Manufacturer")?.value,
            model = info.find("Model")?.value,
            firmware = info.find("FirmwareVersion")?.value,
            mac = mac,
            authRequired = authRequired,
            streams = streams
        )
    }

    fun macAddress(serviceUrl: String, username: String, password: String?): String {
        val interfaces = call(
            "GetNetworkInterfaces", serviceUrl, username, password,
            """<tds:GetNetworkInterfaces xmlns:tds="$DEVICE_NS"/>"""
        )
        val raw = checkNotNull(interfaces.find("Info")?.child("HwAddress")) {
            "GetNetworkInterfaces returned no Info/HwAddress"
        }.value
        val hex = raw.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
        check(hex.length == 12) { "GetNetworkInterfaces returned malformed HwAddress '$raw'" }
        return hex.chunked(2).joinToString(":")
    }

    // Some firmware answers even GetSystemDateAndTime with NotAuthorized; the device clock is then unknown and the
    // phone's clock is used as is.
    private fun readClockOffset(serviceUrl: String) {
        val response = request(serviceUrl, envelope("""<tds:GetSystemDateAndTime xmlns:tds="$DEVICE_NS"/>""", null, null))
        val fault = response.fault
        if (fault != null) {
            if (fault.contains("NotAuthorized")) return
            throw OnvifException("GetSystemDateAndTime at $serviceUrl failed: $fault")
        }
        // UTCDateTime is optional in the ONVIF schema.
        val utc = response.find("UTCDateTime") ?: return
        val date = checkNotNull(utc.child("Date")) { "GetSystemDateAndTime UTCDateTime has no Date" }
        val time = checkNotNull(utc.child("Time")) { "GetSystemDateAndTime UTCDateTime has no Time" }
        fun number(node: Node, name: String) =
            checkNotNull(node.child(name)) { "GetSystemDateAndTime UTCDateTime has no $name" }.value.toInt()
        val deviceTime = LocalDateTime.of(
            number(date, "Year"), number(date, "Month"), number(date, "Day"),
            number(time, "Hour"), number(time, "Minute"), number(time, "Second")
        ).toInstant(ZoneOffset.UTC).toEpochMilli()
        clockOffsetMs = deviceTime - System.currentTimeMillis()
    }

    private fun mediaServiceUrl(serviceUrl: String, username: String, password: String?): String {
        val services = authenticated(
            "GetServices", serviceUrl, username, password,
            """<tds:GetServices xmlns:tds="$DEVICE_NS"><tds:IncludeCapability>false</tds:IncludeCapability></tds:GetServices>"""
        )
        val fault = services.fault
        if (fault == null) {
            val media = services.findAll("Service").firstOrNull { it.child("Namespace")?.value == MEDIA_NS }
            return checkNotNull(media?.child("XAddr")) { "GetServices at $serviceUrl listed no media service" }.value
        }
        // GetCapabilities is the protocol-defined alternative on firmware without GetServices.
        if (!fault.contains("ActionNotSupported")) throw OnvifException("GetServices at $serviceUrl failed: $fault")
        val capabilities = call(
            "GetCapabilities", serviceUrl, username, password,
            """<tds:GetCapabilities xmlns:tds="$DEVICE_NS"><tds:Category>Media</tds:Category></tds:GetCapabilities>"""
        )
        return checkNotNull(capabilities.find("Media")?.child("XAddr")) {
            "GetCapabilities at $serviceUrl returned no Media/XAddr"
        }.value
    }

    private fun call(action: String, url: String, username: String, password: String?, bodyXml: String): Node {
        val response = authenticated(action, url, username, password, bodyXml)
        val fault = response.fault
        if (fault != null) throw OnvifException("$action at $url failed: $fault")
        return response
    }

    /** Returns the response Envelope, which may still carry a fault other than the first NotAuthorized. */
    private fun authenticated(action: String, url: String, username: String, password: String?, bodyXml: String): Node {
        if (!authRequired) {
            val response = request(url, envelope(bodyXml, null, null))
            val fault = response.fault
            if (fault == null || !fault.contains("NotAuthorized")) return response
            authRequired = true
        }
        if (password == null) throw OnvifException("$action at $url requires a login")
        return request(url, envelope(bodyXml, username, password))
    }

    private fun request(url: String, soap: String): Node {
        http.newCall(Request.Builder().url(url).post(soap.toRequestBody(SOAP_MEDIA_TYPE)).build()).execute().use { response ->
            val contentType = response.header("Content-Type")
            // Faults come back as 400/500 with a SOAP body; anything else non-XML is a transport-level failure.
            if (!response.isSuccessful && contentType?.contains("xml") != true) {
                throw OnvifException("$url answered HTTP ${response.code} ${response.message} ($contentType)")
            }
            val envelope = parse(response.body!!.string())
            if (envelope.name != "Envelope") throw OnvifException("$url answered a non-SOAP document <${envelope.name}>")
            if (!response.isSuccessful && envelope.fault == null) {
                throw OnvifException("$url answered HTTP ${response.code} ${response.message} without a SOAP fault")
            }
            return envelope
        }
    }

    private fun envelope(bodyXml: String, username: String?, password: String?): String {
        val security = if (password == null) "" else {
            val nonce = ByteArray(20).also { SecureRandom().nextBytes(it) }
            val created = DateTimeFormatter.ISO_INSTANT.format(
                Instant.ofEpochMilli(System.currentTimeMillis() + clockOffsetMs).truncatedTo(ChronoUnit.SECONDS)
            )
            val digest = MessageDigest.getInstance("SHA-1")
                .digest(nonce + created.toByteArray(Charsets.UTF_8) + password.toByteArray(Charsets.UTF_8))
            val user = checkNotNull(username).replace("&", "&amp;").replace("<", "&lt;")
            """<wsse:Security s:mustUnderstand="1" xmlns:wsse="$WSSE_NS" xmlns:wsu="$WSU_NS">""" +
                """<wsse:UsernameToken><wsse:Username>$user</wsse:Username>""" +
                """<wsse:Password Type="$WSSE_TOKEN_PROFILE#PasswordDigest">${Base64.getEncoder().encodeToString(digest)}</wsse:Password>""" +
                """<wsse:Nonce EncodingType="$WSSE_SOAP_MESSAGE#Base64Binary">${Base64.getEncoder().encodeToString(nonce)}</wsse:Nonce>""" +
                """<wsu:Created>$created</wsu:Created></wsse:UsernameToken></wsse:Security>"""
        }
        return """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<s:Envelope xmlns:s="$SOAP_ENV_NS"><s:Header>$security</s:Header><s:Body>$bodyXml</s:Body></s:Envelope>"""
    }

    private fun parse(xml: String): Node {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))
        val stack = ArrayDeque<Node>()
        var root: Node? = null
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    val node = Node(
                        parser.name,
                        (0 until parser.attributeCount).associate { parser.getAttributeName(it) to parser.getAttributeValue(it) }
                    )
                    if (stack.isEmpty()) root = node else stack.last().children.add(node)
                    stack.addLast(node)
                }
                XmlPullParser.TEXT -> if (stack.isNotEmpty()) stack.last().text.append(parser.text)
                XmlPullParser.END_TAG -> stack.removeLast()
                XmlPullParser.END_DOCUMENT -> return checkNotNull(root) { "empty XML document" }
            }
        }
    }

    /** Namespace-agnostic XML element: names are local names. */
    private class Node(val name: String, val attributes: Map<String, String>) {
        val children = mutableListOf<Node>()
        val text = StringBuilder()
        val value: String get() = text.toString().trim()

        /** Fault text (codes, subcodes, reason) when this Envelope's Body is a SOAP fault. */
        val fault: String? get() = child("Body")?.child("Fault")?.allText()

        fun child(name: String): Node? = children.firstOrNull { it.name == name }
        fun find(name: String): Node? = children.firstNotNullOfOrNull { if (it.name == name) it else it.find(name) }
        fun findAll(name: String): List<Node> = children.flatMap { if (it.name == name) listOf(it) else it.findAll(name) }
        private fun allText(): String =
            (listOf(value) + children.map { it.allText() }).filter { it.isNotEmpty() }.joinToString(" ")
    }

    companion object {
        private const val SOAP_ENV_NS = "http://www.w3.org/2003/05/soap-envelope"
        private const val WSSE_NS = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
        private const val WSU_NS = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"
        private const val WSSE_TOKEN_PROFILE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0"
        private const val WSSE_SOAP_MESSAGE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0"
        private const val DEVICE_NS = "http://www.onvif.org/ver10/device/wsdl"
        private const val MEDIA_NS = "http://www.onvif.org/ver10/media/wsdl"
        private const val SCHEMA_NS = "http://www.onvif.org/ver10/schema"
        private val SOAP_MEDIA_TYPE = "application/soap+xml; charset=utf-8".toMediaType()
    }
}
