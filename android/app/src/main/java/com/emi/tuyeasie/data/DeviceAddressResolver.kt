package com.emi.tuyeasie.data

import android.content.Context
import java.io.IOException

class DeviceAddressException(message: String) : RuntimeException(message)

data class DeviceAddress(val ip: String, val source: AddressSource, val protocolVersion: String?)

/** Finds a device's LAN address through the one channel its capabilities say it answers on. Blocking. */
class DeviceAddressResolver(private val context: Context) {
    fun resolve(
        deviceId: String,
        capabilities: DeviceCapabilities,
        onvifRecord: OnvifRecord?,
        username: String,
        password: String
    ): DeviceAddress {
        // A verified record's address came from the camera's own ONVIF address data point and answered RTSP.
        if (capabilities.tuyaOnvif && onvifRecord != null && onvifRecord.verified) {
            return DeviceAddress(onvifRecord.address, AddressSource.ONVIF_DATA_POINT, null)
        }
        if (capabilities.tuyaLan) {
            val found = TuyaLanScanner.findDevice(deviceId)
                ?: throw DeviceAddressException("The device did not answer Tuya's LAN discovery broadcast.")
            return DeviceAddress(found.ip, AddressSource.TUYA_LAN, found.protocolVersion)
        }
        val mac = capabilities.mac
            ?: throw DeviceAddressException(
                "The device has no local Tuya protocol and Tuya reports no MAC address, so it can't be found on the network."
            )
        val target = normalizeMac(mac)
        val candidates = OnvifDiscovery.discover(context)
        for (candidate in candidates) {
            for (url in candidate.serviceUrls) {
                val candidateMac = try {
                    OnvifClient().macAddress(url, username, password)
                } catch (e: OnvifException) {
                    // Other ONVIF devices on the network may refuse this device's credentials.
                    continue
                } catch (e: IOException) {
                    continue
                }
                if (normalizeMac(candidateMac) == target) {
                    return DeviceAddress(candidate.ip, AddressSource.ONVIF_DISCOVERY, null)
                }
            }
        }
        throw DeviceAddressException(
            "None of the ${candidates.size} ONVIF devices that answered discovery has MAC $mac, " +
                "and the device has no local Tuya protocol to find it by."
        )
    }

    private fun normalizeMac(mac: String) = mac.lowercase().filter { it.isLetterOrDigit() }
}
