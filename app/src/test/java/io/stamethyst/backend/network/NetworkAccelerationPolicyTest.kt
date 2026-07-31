package io.stamethyst.backend.network

import android.net.NetworkCapabilities
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkAccelerationPolicyTest {
    @Test
    fun shouldUseAcceleratedLinks_onlyRequiresConfiguredEnabled() {
        assertTrue(
            NetworkAccelerationPolicy.shouldUseAcceleratedLinks(
                configuredEnabled = true,
                vpnActiveProvider = { false },
                chinaRegionProvider = { true },
            ),
        )
        assertTrue(
            NetworkAccelerationPolicy.shouldUseAcceleratedLinks(
                configuredEnabled = true,
                vpnActiveProvider = { true },
                chinaRegionProvider = { true },
            ),
        )
        assertTrue(
            NetworkAccelerationPolicy.shouldUseAcceleratedLinks(
                configuredEnabled = true,
                vpnActiveProvider = { false },
                chinaRegionProvider = { false },
            ),
        )
        assertFalse(
            NetworkAccelerationPolicy.shouldUseAcceleratedLinks(
                configuredEnabled = false,
                vpnActiveProvider = { false },
                chinaRegionProvider = { true },
            ),
        )
    }

    @Test
    fun shouldBypassAcceleratedLinks_doesNotDisableConfiguredCandidates() {
        assertFalse(
            NetworkAccelerationPolicy.shouldBypassAcceleratedLinks(
                vpnActiveProvider = { false },
                chinaRegionProvider = { true },
            ),
        )
        assertFalse(
            NetworkAccelerationPolicy.shouldBypassAcceleratedLinks(
                vpnActiveProvider = { true },
                chinaRegionProvider = { true },
            ),
        )
        assertFalse(
            NetworkAccelerationPolicy.shouldBypassAcceleratedLinks(
                vpnActiveProvider = { false },
                chinaRegionProvider = { false },
            ),
        )
    }

    @Test
    fun isChinaRegion_acceptsOnlyCnCountryCode() {
        assertTrue(NetworkAccelerationPolicy.isChinaRegion("CN"))
        assertTrue(NetworkAccelerationPolicy.isChinaRegion("cn"))
        assertFalse(NetworkAccelerationPolicy.isChinaRegion("US"))
        assertFalse(NetworkAccelerationPolicy.isChinaRegion(""))
    }

    @Test
    fun hasVpnTransport_usesSystemVpnTransportFlag() {
        assertTrue(
            NetworkAccelerationPolicy.hasVpnTransport { transport ->
                transport == NetworkCapabilities.TRANSPORT_VPN
            },
        )
        assertFalse(
            NetworkAccelerationPolicy.hasVpnTransport { transport ->
                transport == NetworkCapabilities.TRANSPORT_WIFI
            },
        )
    }

    @Test
    fun isExternalVpnActive_ignoresTheLaunchersConnectedEasyTierTunnel() {
        assertFalse(
            NetworkAccelerationPolicy.isExternalVpnActive(
                vpnTransportActive = true,
                easyTierVpnActive = { true },
            ),
        )
        assertTrue(
            NetworkAccelerationPolicy.isExternalVpnActive(
                vpnTransportActive = true,
                easyTierVpnActive = { false },
            ),
        )
        assertFalse(
            NetworkAccelerationPolicy.isExternalVpnActive(
                vpnTransportActive = false,
                easyTierVpnActive = { true },
            ),
        )
    }
}
