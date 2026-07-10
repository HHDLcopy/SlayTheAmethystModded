package io.stamethyst.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CloudControlConfigTest {
    private val stsDepotKeyHex = "af36e1914da16f3e4556bedf7e46a76646b670c91bb6e1d3cca380e16ceb2df6"

    @Test
    fun parseSettings_readsCompactNestedHeartbeatConfig() {
        val defaults = CloudControlSettings(
            heartbeatIntervalSeconds = 600,
            heartbeatWsUrl = "wss://default.example.com/api/presence/ws"
        )

        val parsed = CloudControlConfig.parseSettings(
            """
            {
              "heartbeat": {
                "intervalSeconds": 120,
                "wsUrl": "wss://example.com/ws"
              }
            }
            """.trimIndent(),
            defaults = defaults
        )

        assertNotNull(parsed)
        assertEquals(120, parsed?.heartbeatIntervalSeconds)
        assertEquals("wss://example.com/ws", parsed?.heartbeatWsUrl)
    }

    @Test
    fun parseSettings_acceptsLegacyTopLevelHeartbeatConfig() {
        val defaults = CloudControlSettings(
            heartbeatIntervalSeconds = 600,
            heartbeatWsUrl = "wss://default.example.com/api/presence/ws"
        )

        val parsed = CloudControlConfig.parseSettings(
            """
            {
              "heartbeatIntervalSeconds": 300,
              "presenceHeartbeatWsUrl": "https://example.com/api/presence/ws"
            }
            """.trimIndent(),
            defaults = defaults
        )

        assertNotNull(parsed)
        assertEquals(300, parsed?.heartbeatIntervalSeconds)
        assertEquals("wss://example.com/api/presence/ws", parsed?.heartbeatWsUrl)
    }

    @Test
    fun parseSettings_clampsIntervalAndFallsBackInvalidUrl() {
        val defaults = CloudControlSettings(
            heartbeatIntervalSeconds = 600,
            heartbeatWsUrl = ""
        )

        val parsed = CloudControlConfig.parseSettings(
            """
            {
              "heartbeatIntervalSeconds": 5,
              "heartbeatWsUrl": "ftp://example.com/ws"
            }
            """.trimIndent(),
            defaults = defaults
        )

        assertNotNull(parsed)
        assertEquals(CloudControlConfig.MIN_HEARTBEAT_INTERVAL_SECONDS, parsed?.heartbeatIntervalSeconds)
        assertEquals(defaults.heartbeatWsUrl, parsed?.heartbeatWsUrl)
    }

    @Test
    fun parseSettings_readsNestedQqGroupConfig() {
        val defaults = CloudControlSettings(
            heartbeatIntervalSeconds = 600,
            heartbeatWsUrl = "wss://default.example.com/api/presence/ws",
            qqGroupNumber = "1029305387"
        )

        val parsed = CloudControlConfig.parseSettings(
            """
            {
              "qqGroup": {
                "number": "2233445566"
              }
            }
            """.trimIndent(),
            defaults = defaults
        )

        assertNotNull(parsed)
        assertEquals("2233445566", parsed?.qqGroupNumber)
        assertEquals(
            "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=2233445566&card_type=group&source=qrcode",
            parsed?.qqGroupUrl
        )
    }

    @Test
    fun parseSettings_fallsBackInvalidQqGroupNumber() {
        val defaults = CloudControlSettings(
            heartbeatIntervalSeconds = 600,
            heartbeatWsUrl = "wss://default.example.com/api/presence/ws",
            qqGroupNumber = "1029305387"
        )

        val parsed = CloudControlConfig.parseSettings(
            """
            {
              "qqGroupNumber": "not-a-group"
            }
            """.trimIndent(),
            defaults = defaults
        )

        assertNotNull(parsed)
        assertEquals(defaults.qqGroupNumber, parsed?.qqGroupNumber)
    }

    @Test
    fun parseSettings_readsNestedSteamDepotKeyHex() {
        val parsed = CloudControlConfig.parseSettings(
            """
            {
              "steam": {
                "depotKeys": [
                  {
                    "appId": 646570,
                    "depotId": 646571,
                    "keyHex": "$stsDepotKeyHex"
                  }
                ]
              }
            }
            """.trimIndent()
        )

        assertNotNull(parsed)
        assertEquals(1, parsed?.steamDepotKeys?.size)
        assertEquals(646570L, parsed?.steamDepotKeys?.first()?.appId)
        assertEquals(646571L, parsed?.steamDepotKeys?.first()?.depotId)
        assertEquals(stsDepotKeyHex, parsed?.steamDepotKeys?.first()?.keyHex)
        assertEquals(32, parsed?.steamDepotKeyBytes(646570u, 646571u)?.size)
    }

    @Test
    fun parseSettings_acceptsSteamDepotKeyBase64Alias() {
        val parsed = CloudControlConfig.parseSettings(
            """
            {
              "steamDepotKeys": [
                {
                  "app_id": "646570",
                  "depot_id": "646571",
                  "depotKeyBase64": "rzbhkU2hbz5FVr7ffkanZka2cMkbtuHTzKOA4WzrLfY="
                }
              ]
            }
            """.trimIndent()
        )

        assertNotNull(parsed)
        assertEquals(stsDepotKeyHex, parsed?.steamDepotKeys?.single()?.keyHex)
    }

    @Test
    fun defaultSettings_matchCurrentCloudControlPayload() {
        val defaults = CloudControlConfig.defaultSettings()

        assertEquals(30, defaults.heartbeatIntervalSeconds)
        assertEquals(
            "wss://heartbeat.nas.apricityx.top:23163/api/presence/ws",
            defaults.heartbeatWsUrl
        )
        assertEquals("1029305387", defaults.qqGroupNumber)
    }
}
