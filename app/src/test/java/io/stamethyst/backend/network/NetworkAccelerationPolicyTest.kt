package io.stamethyst.backend.network

import android.net.NetworkCapabilities
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkAccelerationPolicyTest {
    @Test
    fun shouldUseAcceleratedLinks_requiresConfiguredEnabledAndNoVpn() {
        assertTrue(
            NetworkAccelerationPolicy.shouldUseAcceleratedLinks(
                configuredEnabled = true,
                vpnActiveProvider = { false },
            ),
        )
        assertFalse(
            NetworkAccelerationPolicy.shouldUseAcceleratedLinks(
                configuredEnabled = true,
                vpnActiveProvider = { true },
            ),
        )
        assertFalse(
            NetworkAccelerationPolicy.shouldUseAcceleratedLinks(
                configuredEnabled = false,
                vpnActiveProvider = { false },
            ),
        )
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
}
