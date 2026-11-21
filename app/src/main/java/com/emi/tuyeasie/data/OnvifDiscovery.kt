package com.emi.tuyeasie.data

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Xml
import java.io.StringReader
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.util.UUID
import org.xmlpull.v1.XmlPullParser

data class DiscoveredOnvif(val ip: String, val serviceUrls: List<String>)

object OnvifDiscovery {
    private const val MULTICAST_ADDRESS = "239.255.255.250"
    private const val PORT = 3702

    fun discover(context: Context, timeoutMs: Int = 3000): List<DiscoveredOnvif> {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi.createMulticastLock("tuyeasie-onvif")
        lock.setReferenceCounted(false)
        lock.acquire()
        try {
            MulticastSocket().use { socket ->
                val probe = probeMessage().toByteArray(Charsets.UTF_8)
                val target = InetAddress.getByName(MULTICAST_ADDRESS)
                repeat(2) { socket.send(DatagramPacket(probe, probe.size, target, PORT)) }
                val found = linkedMapOf<String, LinkedHashSet<String>>()
                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(65_535)
                while (true) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    socket.soTimeout = remaining.toInt()
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        break
                    }
                    val ip = checkNotNull(packet.address.hostAddress) { "Discovery reply without a sender address" }
                    val addresses = xAddrs(String(packet.data, packet.offset, packet.length, Charsets.UTF_8))
                    if (addresses.isNotEmpty()) found.getOrPut(ip) { linkedSetOf() }.addAll(addresses)
                }
                return found.map { (ip, urls) -> DiscoveredOnvif(ip, urls.toList()) }
            }
        } finally {
            lock.release()
        }
    }

    private fun probeMessage() =
        """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" """ +
            """xmlns:a="http://schemas.xmlsoap.org/ws/2004/08/addressing" """ +
            """xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" """ +
            """xmlns:dn="http://www.onvif.org/ver10/network/wsdl">""" +
            """<s:Header><a:MessageID>uuid:${UUID.randomUUID()}</a:MessageID>""" +
            """<a:To s:mustUnderstand="1">urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To>""" +
            """<a:Action s:mustUnderstand="1">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</a:Action>""" +
            """</s:Header><s:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></s:Body></s:Envelope>"""

    // Other WS-Discovery traffic on the group (our own echoed Probe, printers, Windows hosts) carries no XAddrs.
    private fun xAddrs(xml: String): List<String> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))
        val result = mutableListOf<String>()
        var inMatch = false
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "ProbeMatch" -> inMatch = true
                    "XAddrs" -> if (inMatch) result += parser.nextText().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                }
                XmlPullParser.END_TAG -> if (parser.name == "ProbeMatch") inMatch = false
                XmlPullParser.END_DOCUMENT -> return result
            }
        }
    }
}
