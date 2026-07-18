package io.stamethyst.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class CloudControlConfigTest {
    private val stsDepotKeyHex = "af36e1914da16f3e4556bedf7e46a76646b670c91bb6e1d3cca380e16ceb2df6"

    @Test
    fun parseSettings_readsMinimumOnlineLobbyCompatibleVersion() {
        val parsed = CloudControlConfig.parseSettings(
            """
            {
              "easyTier": {
                "minimumOnlineLobbyCompatibleVersion": "1.5.1-dev1"
              }
            }
            """.trimIndent()
        )

        assertNotNull(parsed)
        assertEquals("1.5.1-dev1", parsed?.easyTier?.minimumOnlineLobbyCompatibleVersion)
    }

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
    fun parseSettings_keepsDefaultSteamDepotKeysWhenResponseOmitsThem() {
        val defaults = CloudControlSettings(
            heartbeatIntervalSeconds = 600,
            heartbeatWsUrl = "wss://default.example.com/api/presence/ws",
            steamDepotKeys = listOf(
                CloudControlSteamDepotKey(
                    appId = 646570L,
                    depotId = 646571L,
                    keyHex = stsDepotKeyHex,
                ),
            ),
        )

        val parsed = CloudControlConfig.parseSettings(
            """
            {
              "heartbeat": {
                "intervalSeconds": 120,
                "wsUrl": "wss://remote.example.com/ws"
              }
            }
            """.trimIndent(),
            defaults = defaults,
        )

        assertNotNull(parsed)
        assertEquals(defaults.steamDepotKeys, parsed?.steamDepotKeys)
        assertEquals(32, parsed?.steamDepotKeyBytes(646570u, 646571u)?.size)
    }

    @Test
    fun parseSettings_readsNestedEasyTierConfig() {
        val defaults = CloudControlSettings(
            heartbeatIntervalSeconds = 600,
            heartbeatWsUrl = "wss://default.example.com/api/presence/ws",
            easyTier = CloudControlEasyTierSettings(
                roomApiBaseUrl = "https://fallback.example.com",
                webConsoleApiBaseUrl = "https://fallback.example.com/console",
                configServerUrl = "udp://fallback.example.com:22020",
                entryNodeUrl = "tcp://fallback.example.com:11010",
                connectTimeoutSeconds = 30,
                statusPollIntervalSeconds = 10,
            ),
        )

        val parsed = CloudControlConfig.parseSettings(
            """
            {
              "easyTier": {
                "enabled": true,
                "roomApiBaseUrl": "https://online.example.com",
                "webConsoleApiBaseUrl": "https://online.example.com/console",
                "configServerUrl": "udp://online.example.com:22020",
                "entryNodeUrl": "tcp://online.example.com:11010",
                "connectTimeoutSeconds": 18,
                "statusPollIntervalSeconds": 9,
                "allowSharedCommunityNetwork": true,
                "defaultMode": "community"
              }
            }
            """.trimIndent(),
            defaults = defaults,
        )

        assertNotNull(parsed)
        assertEquals(true, parsed?.easyTier?.enabled)
        assertEquals("https://online.example.com", parsed?.easyTier?.roomApiBaseUrl)
        assertEquals("https://online.example.com/console", parsed?.easyTier?.webConsoleApiBaseUrl)
        assertEquals("udp://online.example.com:22020", parsed?.easyTier?.configServerUrl)
        assertEquals("tcp://online.example.com:11010", parsed?.easyTier?.entryNodeUrl)
        assertEquals(18, parsed?.easyTier?.connectTimeoutSeconds)
        assertEquals(9, parsed?.easyTier?.statusPollIntervalSeconds)
        assertEquals(true, parsed?.easyTier?.allowSharedCommunityNetwork)
        assertEquals("community", parsed?.easyTier?.defaultMode)
        assertTrue(parsed?.easyTier?.isConfigured == true)
    }

    @Test
    fun parseSettings_normalizesEasyTierAliasesAndInvalidValues() {
        val defaults = CloudControlSettings(
            heartbeatIntervalSeconds = 600,
            heartbeatWsUrl = "wss://default.example.com/api/presence/ws",
            easyTier = CloudControlEasyTierSettings(
                enabled = true,
                roomApiBaseUrl = "https://fallback.example.com",
                webConsoleApiBaseUrl = "https://fallback.example.com/console",
                configServerUrl = "udp://fallback.example.com:22020",
                entryNodeUrl = "tcp://fallback.example.com:11010",
                connectTimeoutSeconds = 30,
                statusPollIntervalSeconds = 10,
                allowSharedCommunityNetwork = false,
                defaultMode = "room",
            ),
        )

        val parsed = CloudControlConfig.parseSettings(
            """
            {
              "easyTierEnabled": "0",
              "easyTierRoomApiBaseUrl": "ftp://online.example.com",
              "easyTierWebConsoleApiBaseUrl": "",
              "easyTierConfigServerUrl": "online.example.com:22020",
              "easyTierEntryNodeUrl": "tcp://",
              "easyTierConnectTimeoutSeconds": 999,
              "easyTierStatusPollIntervalSeconds": 999,
              "easyTierAllowSharedCommunityNetwork": "yes",
              "easyTierDefaultMode": "shared"
            }
            """.trimIndent(),
            defaults = defaults,
        )

        assertNotNull(parsed)
        assertFalse(parsed?.easyTier?.enabled ?: true)
        assertEquals(defaults.easyTier.roomApiBaseUrl, parsed?.easyTier?.roomApiBaseUrl)
        assertEquals(defaults.easyTier.webConsoleApiBaseUrl, parsed?.easyTier?.webConsoleApiBaseUrl)
        assertEquals(defaults.easyTier.configServerUrl, parsed?.easyTier?.configServerUrl)
        assertEquals(defaults.easyTier.entryNodeUrl, parsed?.easyTier?.entryNodeUrl)
        assertEquals(CloudControlConfig.MAX_EASYTIER_CONNECT_TIMEOUT_SECONDS, parsed?.easyTier?.connectTimeoutSeconds)
        assertEquals(
            CloudControlConfig.MAX_EASYTIER_STATUS_POLL_INTERVAL_SECONDS,
            parsed?.easyTier?.statusPollIntervalSeconds,
        )
        assertEquals(true, parsed?.easyTier?.allowSharedCommunityNetwork)
        assertEquals("community", parsed?.easyTier?.defaultMode)
        assertFalse(parsed?.easyTier?.isConfigured ?: true)
    }

    @Test
    fun packagedDefaultCloudControlPayload_containsSteamDepotKey() {
        val parsed = CloudControlConfig.parseSettings(readPackagedDefaultCloudControlPayload())

        assertNotNull(parsed)
        assertEquals(30, parsed?.heartbeatIntervalSeconds)
        assertEquals(
            "wss://heartbeat.nas.apricityx.top:23163/api/presence/ws",
            parsed?.heartbeatWsUrl,
        )
        assertEquals("1051836431", parsed?.qqGroupNumber)
        assertTrue(parsed?.easyTier?.enabled == true)
        assertEquals("1.5.1-dev1", parsed?.easyTier?.minimumOnlineLobbyCompatibleVersion)
        assertEquals(
            "https://heartbeat.nas.apricityx.top:23163",
            parsed?.easyTier?.roomApiBaseUrl,
        )
        assertEquals(
            "https://heartbeat.nas.apricityx.top:23163",
            parsed?.easyTier?.webConsoleApiBaseUrl,
        )
        assertEquals("udp://frp-dry.com:12333", parsed?.easyTier?.configServerUrl)
        assertEquals("tcp://frp-dog.com:12332", parsed?.easyTier?.entryNodeUrl)
        assertEquals(1, parsed?.steamDepotKeys?.size)
        assertEquals(646570L, parsed?.steamDepotKeys?.single()?.appId)
        assertEquals(646571L, parsed?.steamDepotKeys?.single()?.depotId)
        assertEquals(stsDepotKeyHex, parsed?.steamDepotKeys?.single()?.keyHex)
    }

    @Test
    fun packagedLocalTestCloudControlPayload_pointsEveryOnlineEndpointToLocalService() {
        val parsed = CloudControlConfig.parseSettings(readPackagedCloudControlPayload("cloud-control-test.json"))

        assertNotNull(parsed)
        assertEquals("ws://192.168.31.137:3001/api/presence/ws", parsed?.heartbeatWsUrl)
        assertTrue(parsed?.easyTier?.enabled == true)
        assertEquals("1.5.1-dev1", parsed?.easyTier?.minimumOnlineLobbyCompatibleVersion)
        assertEquals("http://192.168.31.137:3001", parsed?.easyTier?.roomApiBaseUrl)
        assertEquals("http://192.168.31.137:3001", parsed?.easyTier?.webConsoleApiBaseUrl)
        assertEquals("udp://192.168.31.137:22020", parsed?.easyTier?.configServerUrl)
        assertEquals("tcp://192.168.31.137:11010", parsed?.easyTier?.entryNodeUrl)
    }

    @Test
    fun defaultSettings_returnsEmergencyFallbackWithoutAndroidContext() {
        val defaults = CloudControlConfig.defaultSettings()

        assertEquals(30, defaults.heartbeatIntervalSeconds)
        assertEquals(
            "wss://heartbeat.nas.apricityx.top:23163/api/presence/ws",
            defaults.heartbeatWsUrl
        )
        assertEquals("1029305387", defaults.qqGroupNumber)
        assertTrue(defaults.steamDepotKeys.isEmpty())
        assertFalse(defaults.easyTier.enabled)
        assertEquals("", defaults.easyTier.entryNodeUrl)
        assertEquals("room", defaults.easyTier.defaultMode)
    }

    private fun readPackagedDefaultCloudControlPayload(): String {
        return readPackagedCloudControlPayload("cloud-control.json")
    }

    private fun readPackagedCloudControlPayload(fileName: String): String {
        val candidates = listOf(
            Path.of("app", "src", "main", "assets", fileName),
            Path.of("src", "main", "assets", fileName),
        )
        val path = candidates.firstOrNull { candidate -> Files.isRegularFile(candidate) }
            ?: error("Packaged cloud control payload '$fileName' was not found.")
        return String(Files.readAllBytes(path), Charsets.UTF_8)
    }
}
