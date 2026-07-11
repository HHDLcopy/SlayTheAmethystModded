package io.stamethyst.backend.easytier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EasyTierRuntimeConfigBuilderTest {
    @Test
    fun build_usesUpstreamTomlShape() {
        val config = EasyTierRuntimeConfigBuilder.build(
            sessionConfig = EasyTierRoomSessionConfig(
                sessionId = "lan_abc123",
                roomId = "Room One",
                mode = EasyTierNetworkMode.Room,
                entryNodeUrl = "tcp://online.example.com:11010",
                configServerUrl = "udp://online.example.com:22020",
                aclGroup = "room-room-one",
                networkSecret = "secret-value",
            ),
            playerId = "player-1",
        )

        assertTrue(config.toml.contains("instance_name = \"sts-android-lan-abc123-"))
        assertTrue(config.toml.contains("hostname = \"sts-player-1-"))
        assertTrue(config.toml.contains("dhcp = true"))
        assertTrue(config.toml.contains("listeners = []"))
        assertTrue(config.toml.contains("[network_identity]"))
        assertTrue(config.toml.contains("network_name = \"${config.networkName}\""))
        assertTrue(config.toml.contains("network_secret = \"secret-value\""))
        assertTrue(config.toml.contains("[[peer]]"))
        assertTrue(config.toml.contains("uri = \"tcp://online.example.com:11010\""))
        assertEquals(listOf("tcp://online.example.com:11010"), config.peerUrls)
    }

    @Test
    fun buildNetworkName_preservesHashSuffixForLongRoomIds() {
        val longRoomId = "Room-" + "A".repeat(200)
        val networkName = EasyTierRuntimeConfigBuilder.buildNetworkName(longRoomId)

        assertTrue(networkName.length <= 96)
        assertTrue(networkName.matches(Regex("sts-room-a+-[0-9a-f]{12}")))
        assertFalse(networkName.endsWith("-"))
    }
}
