package com.emi.tuyeasie.data

import android.content.Context
import java.io.IOException

class DeviceAddressException(message: String) : RuntimeException(message)

data class DeviceAddress(val ip: String, val source: AddressSource, val protocolVersion: String?)

/** Finds a device's LAN address through Tuya LAN broadcast or ONVIF MAC discovery. Blocking. */
class DeviceAddressResolver(private val context: Context) {
    fun resolve(
        deviceId: String,
        capabilities: DeviceCapabilities,
        onvifRecord: OnvifRecord?,
        credentials: List<Credentials>
    ): DeviceAddress {
        // A verified record's address came from the camera's own ONVIF address data point and answered ONVIF discovery.
        if (capabilities.tuyaOnvif && onvifRecord != null && onvifRecord.verified) {
            return DeviceAddress(onvifRecord.address, AddressSource.ONVIF_DATA_POINT, null)
        }
        // Devices with Tuya data points speak the Tuya LAN protocol; devices with 0 data points are ONVIF-only even
        // when the Tuya SDK leaves hasLanCommunication set on the bean.
        if (capabilities.supportsTuyaLocal) {
            TuyaLanScanner.findDevice(deviceId)?.let { found ->
                return DeviceAddress(found.ip, AddressSource.TUYA_LAN, found.protocolVersion)
            }
            capabilities.mac?.let { mac ->
                discoverByOnvifMac(mac, credentials)?.let { return it }
            }
            throw DeviceAddressException("The device did not answer Tuya's LAN discovery broadcast.")
        }
        val mac = capabilities.mac
        if (mac != null) {
            discoverByOnvifMac(mac, credentials)?.let { return it }
        }
        if (capabilities.tuyaLan) {
            TuyaLanScanner.findDevice(deviceId)?.let { found ->
                return DeviceAddress(found.ip, AddressSource.TUYA_LAN, found.protocolVersion)
            }
        }
        if (mac == null) {
            throw DeviceAddressException(
                "The device has no local Tuya protocol and Tuya reports no MAC address, so it can't be found on the network."
            )
        }
        throw DeviceAddressException(
            "No ONVIF device on the network matched MAC $mac, and the device did not answer Tuya's LAN discovery."
        )
    }

    private fun discoverByOnvifMac(mac: String, credentials: List<Credentials>): DeviceAddress? {
        val target = normalizeMac(mac)
        for (candidate in OnvifDiscovery.discover(context)) {
            for (url in candidate.serviceUrls) {
                val candidateMac = try {
                    OnvifClient().macAddress(url, credentials)
                } catch (e: OnvifException) {
                    continue
                } catch (e: IOException) {
                    continue
                }
                if (normalizeMac(candidateMac) == target) {
                    return DeviceAddress(candidate.ip, AddressSource.ONVIF_DISCOVERY, null)
                }
            }
        }
        return null
    }

    private fun normalizeMac(mac: String) = mac.lowercase().filter { it.isLetterOrDigit() }
}
