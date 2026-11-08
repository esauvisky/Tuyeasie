package com.emi.tuyeasie.data

import com.alibaba.fastjson.JSON
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class LanDiscoveryResult(
    val ip: String,
    val protocolVersion: String?
)

object TuyaLanScanner {
    private val UDP_KEY: ByteArray = MessageDigest.getInstance("MD5")
        .digest("yGAdlopoPVldABfn".toByteArray(Charsets.UTF_8))

    fun findDevice(deviceId: String, timeoutMillis: Int = 8_000): LanDiscoveryResult? {
        val sockets = listOf(6666, 6667, 7000).mapNotNull { port ->
            try {
                DatagramSocket(port).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 250
                }
            } catch (_: Exception) {
                // Another listener on the phone may hold one of the UDP ports; scan the remaining ports.
                null
            }
        }
        check(sockets.isNotEmpty()) { "Could not bind any Tuya LAN discovery UDP port (6666, 6667, 7000)" }
        try {
            sendDiscoveryProbe()
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
            val buffer = ByteArray(4096)
            var nextProbeAt = System.nanoTime() + 2_000_000_000L
            while (System.nanoTime() < deadline) {
                if (System.nanoTime() >= nextProbeAt) {
                    sendDiscoveryProbe()
                    nextProbeAt = System.nanoTime() + 2_000_000_000L
                }
                for (socket in sockets) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val parsed = decodeBroadcast(packet.data, packet.length, packet.address.hostAddress)
                        ?: continue
                    if (parsed.first == deviceId) {
                        return parsed.second
                    }
                }
            }
            return null
        } finally {
            sockets.forEach { it.close() }
        }
    }

    private fun sendDiscoveryProbe() {
        val payload = """{"from":"app","ip":"255.255.255.255"}""".toByteArray(Charsets.UTF_8)
        val iv = ByteArray(12) { (it + 1).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(UDP_KEY, "AES"), GCMParameterSpec(128, iv))
        val header = ByteBuffer.allocate(18)
            .putInt(0x00006699)
            .putShort(0)
            .putInt(0)
            .putInt(0x00000025)
            .putInt(iv.size + payload.size + 16)
            .array()
        cipher.updateAAD(header, 4, 14)
        val encrypted = cipher.doFinal(payload)
        val frame = ByteBuffer.allocate(18 + iv.size + encrypted.size + 4)
            .put(header)
            .put(iv)
            .put(encrypted)
            .putInt(0x00009966)
            .array()
        DatagramSocket().use { sender ->
            sender.broadcast = true
            val broadcastAddress = InetAddress.getByName("255.255.255.255")
            sender.send(DatagramPacket(frame, frame.size, broadcastAddress, 7000))
        }
    }

    private fun decodeBroadcast(data: ByteArray, length: Int, fallbackIp: String?): Pair<String, LanDiscoveryResult>? {
        if (length < 28) return null
        val prefix = ByteBuffer.wrap(data, 0, 4).int
        val jsonText = when (prefix) {
            0x000055AA -> {
                val payload = data.copyOfRange(20, length - 8)
                val decrypted = if (payload.isNotEmpty() && payload[0] == '{'.code.toByte()) {
                    payload
                } else {
                    // Any LAN host can send to these ports; a payload that is not whole AES blocks is not a Tuya broadcast.
                    if (payload.isEmpty() || payload.size % 16 != 0) return null
                    val cipher = Cipher.getInstance("AES/ECB/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(UDP_KEY, "AES"))
                    cipher.doFinal(payload)
                }
                val text = String(decrypted, Charsets.UTF_8)
                val start = text.indexOf('{')
                val end = text.lastIndexOf('}')
                if (start < 0 || end <= start) return null
                text.substring(start, end + 1)
            }
            0x00006699 -> {
                if (length < 34) return null
                val iv = data.copyOfRange(18, 30)
                val ciphertextAndTag = data.copyOfRange(30, length - 4)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(UDP_KEY, "AES"), GCMParameterSpec(128, iv))
                cipher.updateAAD(data, 4, 14)
                val decrypted = try {
                    cipher.doFinal(ciphertextAndTag)
                } catch (_: Exception) {
                    return null
                }
                val withoutReturnCode = if (decrypted.size > 4 && decrypted[0] != '{'.code.toByte()) {
                    decrypted.copyOfRange(4, decrypted.size)
                } else {
                    decrypted
                }
                String(withoutReturnCode, Charsets.UTF_8)
            }
            else -> return null
        }
        val json = try {
            JSON.parseObject(jsonText)
        } catch (_: Exception) {
            return null
        } ?: return null
        val gwId = json.getString("gwId") ?: return null
        val ip = json.getString("ip")?.takeIf { it.isNotBlank() } ?: fallbackIp ?: return null
        val version = json.getString("version")?.takeIf { it.isNotBlank() }
        return gwId to LanDiscoveryResult(ip, version)
    }
}
