package io.stamethyst.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CloudControlConfigTest {
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
    fun defaultSettings_doesNotFallbackToScfPresenceEndpoint() {
        assertEquals("", CloudControlConfig.defaultSettings().heartbeatWsUrl)
    }
}
