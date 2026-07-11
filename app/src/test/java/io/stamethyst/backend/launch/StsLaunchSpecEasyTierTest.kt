package io.stamethyst.backend.launch

import io.stamethyst.backend.easytier.EasyTierConnectionSnapshot
import io.stamethyst.backend.easytier.EasyTierConnectionStatus
import io.stamethyst.backend.easytier.EasyTierNetworkMode
import org.junit.Assert.assertEquals
import org.junit.Test

class StsLaunchSpecEasyTierTest {
    @Test
    fun buildEasyTierTogetherInSpireJvmProperties_returnsPortOnlyWhenSnapshotMissing() {
        assertEquals(
            mapOf("amethyst.easytier.together_in_spire.port" to "33455"),
            StsLaunchSpec.buildEasyTierTogetherInSpireJvmProperties(null),
        )
    }

    @Test
    fun buildEasyTierTogetherInSpireJvmProperties_includesOwnerIpAndRoomMetadata() {
        val properties = StsLaunchSpec.buildEasyTierTogetherInSpireJvmProperties(
            EasyTierConnectionSnapshot(
                enabled = true,
                canConnect = true,
                status = EasyTierConnectionStatus.CONNECTED,
                mode = EasyTierNetworkMode.Room,
                roomId = "room-1",
                assignedIpv4Cidr = "10.144.0.2/24",
                currentPlayerId = "player-2",
                roomOwnerPlayerId = "player-1",
                roomOwnerIpv4Cidr = "10.144.0.1/24",
            ),
        )

        assertEquals("33455", properties["amethyst.easytier.together_in_spire.port"])
        assertEquals("10.144.0.1", properties["amethyst.easytier.together_in_spire.host_ip"])
        assertEquals("10.144.0.2/24", properties["amethyst.easytier.assigned_ipv4_cidr"])
        assertEquals("player-2", properties["amethyst.easytier.current_player_id"])
        assertEquals("player-1", properties["amethyst.easytier.room_owner_player_id"])
        assertEquals("room-1", properties["amethyst.easytier.room_id"])
    }

    @Test
    fun buildEasyTierTogetherInSpireJvmProperties_withholdsIpUntilVpnIsConnected() {
        val properties = StsLaunchSpec.buildEasyTierTogetherInSpireJvmProperties(
            EasyTierConnectionSnapshot(
                enabled = true,
                canConnect = true,
                status = EasyTierConnectionStatus.SESSION_READY,
                mode = EasyTierNetworkMode.Room,
                assignedIpv4Cidr = "10.144.0.2/24",
                roomOwnerIpv4Cidr = "10.144.0.1/24",
            ),
        )

        assertEquals(mapOf("amethyst.easytier.together_in_spire.port" to "33455"), properties)
    }

    @Test
    fun extractEasyTierIpv4Host_stripsPrefixAndRejectsInvalidInput() {
        assertEquals("10.144.0.1", StsLaunchSpec.extractEasyTierIpv4Host("10.144.0.1/24"))
        assertEquals("", StsLaunchSpec.extractEasyTierIpv4Host("not-an-ip"))
        assertEquals("", StsLaunchSpec.extractEasyTierIpv4Host("300.1.1.1/24"))
        assertEquals("", StsLaunchSpec.extractEasyTierIpv4Host(""))
    }
}
