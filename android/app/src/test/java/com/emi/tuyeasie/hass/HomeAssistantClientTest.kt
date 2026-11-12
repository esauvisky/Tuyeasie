package com.emi.tuyeasie.hass

import com.alibaba.fastjson.JSON
import com.emi.tuyeasie.data.ActivatedDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAssistantClientTest {
    @Test
    fun convertsHttpUrlsAndAppendsWebSocketEndpoint() {
        assertEquals(
            "ws://homeassistant.local:8123/api/websocket",
            HomeAssistantProtocol.webSocketUrl("http://homeassistant.local:8123/")
        )
        assertEquals(
            "wss://example.net/hass/api/websocket",
            HomeAssistantProtocol.webSocketUrl("https://example.net/hass")
        )
    }

    @Test
    fun constructsImportPayloadWithDefaultsAndOptionalFields() {
        val device = ActivatedDevice(
            deviceId = "test-device-id",
            localKey = "test-local-key",
            name = "Test device",
            productId = "test-product-id",
            protocolVersion = null,
            category = null,
            ip = null
        )

        val payload = HomeAssistantProtocol.importPayload(device, 42)

        assertEquals(42L, payload["id"])
        assertEquals("tuya_local/import_device", payload["type"])
        assertEquals(1, payload["import_version"])
        assertEquals("test-device-id", payload["device_id"])
        assertEquals("test-local-key", payload["local_key"])
        assertEquals("Test device", payload["name"])
        assertEquals("test-product-id", payload["product_id"])
        assertEquals("auto", payload["protocol_version"])
        assertFalse(payload.containsKey("category"))
        assertFalse(payload.containsKey("ip"))

        val optionalPayload = HomeAssistantProtocol.importPayload(
            device.copy(protocolVersion = "3.4", category = "switch", ip = "192.168.1.20"),
            43
        )
        assertEquals("3.4", optionalPayload["protocol_version"])
        assertEquals("switch", optionalPayload["category"])
        assertEquals("192.168.1.20", optionalPayload["ip"])
    }

    @Test
    fun normalizesUnsupportedProtocolAndOmitsBlankOrPublicMetadata() {
        val device = ActivatedDevice(
            deviceId = "id",
            localKey = "key",
            name = "name",
            productId = "product",
            protocolVersion = "3.4.1",
            category = " ",
            ip = "8.8.8.8"
        )

        val payload = HomeAssistantProtocol.importPayload(device, 44)

        assertEquals("auto", payload["protocol_version"])
        assertFalse(payload.containsKey("category"))
        assertFalse(payload.containsKey("ip"))
    }

    @Test
    fun acceptsOnlyRfc1918IpAddresses() {
        val device = ActivatedDevice("id", "key", "name", "product", "3.3", "switch", null)
        val privateAddresses = listOf("10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.1")
        val excludedAddresses = listOf(
            "172.15.0.1", "172.32.0.1", "192.0.2.10", "256.1.1.1", "10.1.1", "not-an-ip", " "
        )

        privateAddresses.forEach { address ->
            val payload = HomeAssistantProtocol.importPayload(device.copy(ip = address), 45)
            assertEquals(address, payload["ip"])
        }
        excludedAddresses.forEach { address ->
            val payload = HomeAssistantProtocol.importPayload(device.copy(ip = address), 46)
            assertFalse("Expected $address to be omitted", payload.containsKey("ip"))
        }
    }

    @Test
    fun mapsDiscoveryFailureToLocalNetworkMessageWithoutChangingOtherCodes() {
        val discovery = serverError("discovery_failed")

        assertEquals(
            "Home Assistant could not find the device on the local network. " +
                "Check that it is powered on and connected to the same LAN.",
            discovery.publicMessage
        )
        assertEquals(HomeAssistantError.UNKNOWN_COMMAND, serverError("unknown_command").error)
        assertEquals(HomeAssistantError.UNAUTHORIZED, serverError("unauthorized").error)
        assertEquals(HomeAssistantError.SERVER_ERROR, serverError("another_error").error)
    }

    @Test
    fun constructsOnvifConfigurePayload() {
        val payload = HomeAssistantProtocol.onvifConfigurePayload("Porch", "192.168.1.30", 8000, "admin", "secret")

        assertEquals(
            mapOf(
                "name" to "Porch",
                "host" to "192.168.1.30",
                "port" to 8000,
                "username" to "admin",
                "password" to "secret"
            ),
            payload
        )
    }

    @Test
    fun readsCurrentAddressFromOnvifReconfigureForm() {
        val form = JSON.parseObject(
            """
            {"type":"form","step_id":"reconfigure","errors":{},"data_schema":[
              {"name":"host","required":true,"type":"string","description":{"suggested_value":"192.168.1.30"}},
              {"name":"port","required":true,"type":"integer","description":{"suggested_value":8000}},
              {"name":"username","optional":true,"default":"","type":"string","description":{"suggested_value":"admin"}},
              {"name":"password","optional":true,"default":"","type":"string"}
            ]}
            """.trimIndent()
        )

        assertEquals("192.168.1.30" to 8000, HomeAssistantProtocol.onvifReconfigureAddress(form))
    }

    @Test
    fun mapsOnvifConfigureErrorsAndAbortsToMessagesWithHomeAssistantKeys() {
        val authFailed = HomeAssistantProtocol.onvifFlowFailure(
            JSON.parseObject(
                """{"type":"form","step_id":"configure","errors":{"password":"auth_failed"},""" +
                    """"description_placeholders":{"error":"Sender not Authorized"}}"""
            )
        )
        assertEquals(HomeAssistantError.SERVER_ERROR, authFailed.error)
        assertEquals(
            "Home Assistant could not log in to the ONVIF device: Sender not Authorized (auth_failed).",
            authFailed.publicMessage
        )

        val noService = HomeAssistantProtocol.onvifFlowFailure(
            JSON.parseObject("""{"type":"form","step_id":"configure","errors":{"port":"no_onvif_service"}}""")
        )
        assertTrue(noService.publicMessage.endsWith("(no_onvif_service)."))

        val unknownError = HomeAssistantProtocol.onvifFlowFailure(
            JSON.parseObject("""{"type":"form","step_id":"configure","errors":{"base":"cannot_connect"}}""")
        )
        assertEquals("Home Assistant rejected the ONVIF configuration (base: cannot_connect).", unknownError.publicMessage)

        val noH264 = HomeAssistantProtocol.onvifFlowFailure(
            JSON.parseObject("""{"type":"abort","reason":"no_h264"}""")
        )
        assertTrue(noH264.publicMessage.endsWith("(no_h264)."))

        val otherAbort = HomeAssistantProtocol.onvifFlowFailure(
            JSON.parseObject("""{"type":"abort","reason":"unique_id_mismatch"}""")
        )
        assertEquals("Home Assistant aborted the ONVIF setup (unique_id_mismatch).", otherAbort.publicMessage)
    }
}
