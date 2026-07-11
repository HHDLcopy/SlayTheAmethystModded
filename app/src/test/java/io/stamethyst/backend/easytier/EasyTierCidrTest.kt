package io.stamethyst.backend.easytier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EasyTierCidrTest {
    @Test
    fun normalizeEasyTierIpv4Route_truncatesHostBits() {
        assertEquals(
            EasyTierIpv4Cidr("10.144.144.0", 24),
            normalizeEasyTierIpv4Route("10.144.144.42/24")
        )
    }

    @Test
    fun formatEasyTierIpv4Inet_formatsUnsignedIpv4Values() {
        assertEquals("192.168.1.2/24", formatEasyTierIpv4Inet(0xC0A80102L, 24))
    }

    @Test
    fun parseEasyTierIpv4Cidr_rejectsInvalidInput() {
        assertNull(parseEasyTierIpv4Cidr("10.0.0.1/33"))
        assertNull(parseEasyTierIpv4Cidr("not-an-ip"))
    }

    @Test
    fun isDefaultEasyTierIpv4Route_detectsDefaultOnly() {
        assertTrue(isDefaultEasyTierIpv4Route("0.0.0.0/0"))
        assertFalse(isDefaultEasyTierIpv4Route("10.0.0.0/8"))
    }
}
