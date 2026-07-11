package io.stamethyst.backend.easytier

import io.stamethyst.config.CloudControlEasyTierSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EasyTierConfigRepositoryTest {
    @Test
    fun fromCloudControl_forcesRoomModeWhenSharedCommunityNetworkIsDisabled() {
        val resolved = EasyTierConfigRepository.fromCloudControl(
            CloudControlEasyTierSettings(
                enabled = false,
                entryNodeUrl = "tcp://online.example.com:11010",
                allowSharedCommunityNetwork = false,
                defaultMode = "community",
            )
        )

        assertEquals(EasyTierNetworkMode.Room, resolved.defaultMode)
        assertTrue(resolved.enabled)
        assertTrue(resolved.canConnect)
    }

    @Test
    fun fromCloudControl_preservesCommunityModeWhenSharedCommunityNetworkIsAllowed() {
        val resolved = EasyTierConfigRepository.fromCloudControl(
            CloudControlEasyTierSettings(
                enabled = false,
                roomApiBaseUrl = "https://online.example.com",
                webConsoleApiBaseUrl = "https://online.example.com",
                configServerUrl = "udp://online.example.com:22020",
                entryNodeUrl = "tcp://online.example.com:11010",
                allowSharedCommunityNetwork = true,
                defaultMode = "community",
            )
        )

        assertEquals(EasyTierNetworkMode.Community, resolved.defaultMode)
        assertEquals("https://online.example.com", resolved.roomApiBaseUrl)
        assertEquals("udp://online.example.com:22020", resolved.configServerUrl)
        assertTrue(resolved.enabled)
        assertTrue(resolved.canConnect)
    }

    @Test
    fun fromCloudControl_reportsCannotConnectWithoutEntryNode() {
        val resolved = EasyTierConfigRepository.fromCloudControl(
            CloudControlEasyTierSettings(
                enabled = false,
                allowSharedCommunityNetwork = true,
                defaultMode = "room",
            )
        )

        assertTrue(resolved.enabled)
        assertFalse(resolved.canConnect)
    }
}
