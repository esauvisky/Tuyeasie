package com.emi.tuyeasie.hass

import com.alibaba.fastjson.JSON
import com.emi.tuyeasie.data.ActivatedDevice
import com.emi.tuyeasie.data.TuyaDataPointSchema
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
    fun acceptsOnlyRfc1918IpAddresses() {
        listOf("10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.1").forEach { address ->
            assertTrue("Expected $address to be private", HomeAssistantProtocol.isRfc1918Address(address))
        }
        listOf("172.15.0.1", "172.32.0.1", "192.0.2.10", "256.1.1.1", "10.1.1", "not-an-ip", " ").forEach { address ->
            assertFalse("Expected $address to be rejected", HomeAssistantProtocol.isRfc1918Address(address))
        }
    }

    @Test
    fun mapsWebSocketErrorCodes() {
        assertEquals(HomeAssistantError.UNAUTHORIZED, serverError("unauthorized").error)
        assertEquals(HomeAssistantError.INVALID_FORMAT, serverError("invalid_format").error)
        assertEquals(HomeAssistantError.SERVER_ERROR, serverError("another_error").error)
    }

    @Test
    fun buildsLocalTuyaDeviceAndEntities() {
        fun dp(id: String, code: String, mode: String, schemaType: String, property: String, type: String = "obj") =
            TuyaDataPointSchema(id, code, code, mode, type, property, schemaType)
        val points = listOf(
            dp("1", "switch_led", "rw", "bool", "{}"),
            dp("2", "motion_alarm", "ro", "bool", "{}"),
            dp("3", "night_mode", "rw", "enum", """{"range":["auto","off","on"]}"""),
            dp("4", "temperature", "ro", "value", """{"min":-400,"max":800,"scale":1,"step":1,"unit":"°C"}"""),
            dp("5", "volume", "rw", "value", """{"min":0,"max":10,"step":1}"""),
            dp("6", "ptz_control", "rw", "raw", "{}", type = "raw")
        )
        val device = ActivatedDevice("dev", "key", "Garage camera", "product", "2.2", "sp", null)

        val payload = HomeAssistantProtocol.localTuyaDevicePayload(device, "192.168.1.30", points)
        assertEquals("auto", payload["protocol_version"])
        assertEquals("1,2,3,4,5,6", payload["manual_dps_strings"])
        assertEquals("3.4", HomeAssistantProtocol.localTuyaDevicePayload(device.copy(protocolVersion = "3.4"), "h", points)["protocol_version"])

        val entities = HomeAssistantProtocol.localTuyaEntities(points)
        assertEquals(listOf("switch", "binary_sensor", "select", "sensor", "number"), entities.map { it.platform })
        assertEquals("Switch led", entities[0].fields["friendly_name"])
        assertEquals(mapOf("auto" to "auto", "off" to "off", "on" to "on"), entities[2].fields["select_options"])
        assertEquals(0.1, entities[3].fields["scaling"] as Double, 1e-9)
        assertEquals("°C", entities[3].fields["unit_of_measurement"])
        assertEquals(10.0, entities[4].fields["max_value"])
        assertFalse(entities[4].fields.containsKey("scaling"))
    }

    @Test
    fun turnsIrBlasterDataPointsIntoOneRemote() {
        fun dp(id: String, code: String, mode: String, schemaType: String, property: String = "{}") =
            TuyaDataPointSchema(id, code, code, mode, "obj", property, schemaType)
        val points = listOf(
            dp("101", "va_temperature", "ro", "value", """{"min":-200,"max":600,"scale":1,"step":1,"unit":"°C"}"""),
            dp("201", "ir_send", "rw", "string"),
            dp("202", "ir_study_code", "ro", "string")
        )

        val entities = HomeAssistantProtocol.localTuyaEntities(points)
        assertEquals(listOf("remote", "sensor"), entities.map { it.platform })
        assertEquals("201", entities[0].fields["id"])
        assertEquals("202", entities[0].fields["receive_dp"])
        assertEquals("101", entities[1].fields["id"])
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

    @Test
    fun buildsEntitySlugsAndNumbersTakenIds() {
        assertEquals("garage_camera_2", HomeAssistantProtocol.entitySlug("  Garage Camera #2 "))
        assertEquals("camera_do_portao", HomeAssistantProtocol.entitySlug("Câmera do Portão"))
        assertEquals("", HomeAssistantProtocol.entitySlug("摄像头"))
        assertEquals("camera.garage", HomeAssistantProtocol.freeEntityId("camera.garage", setOf("camera.other")))
        assertEquals(
            "camera.garage_3",
            HomeAssistantProtocol.freeEntityId("camera.garage", setOf("camera.garage", "camera.garage_2"))
        )
    }
}
