package io.stamethyst.backend.easytier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class EasyTierRuntimeInfoParserTest {
    @Test
    fun parse_readsSnakeCaseProtobufJson() {
        val info = EasyTierRuntimeInfoParser.parse(
            rawJson = """
                {
                  "map": {
                    "sts-android-lan": {
                      "running": true,
                      "my_node_info": {
                        "virtual_ipv4": {
                          "address": { "addr": 168430090 },
                          "network_length": 24
                        }
                      },
                      "routes": [
                        { "proxy_cidrs": ["10.20.30.40/24", "0.0.0.0/0"] },
                        { "proxy_cidrs": ["10.20.30.0/24"] }
                      ],
                      "peers": [{}, {}]
                    }
                  }
                }
            """.trimIndent(),
            instanceName = "sts-android-lan",
        )

        assertNotNull(info)
        requireNotNull(info)
        assertEquals(true, info.running)
        assertEquals("10.10.10.10/24", info.virtualIpv4Cidr)
        assertEquals(
            listOf("10.10.10.0/24", "10.20.30.0/24"),
            info.routeCidrs
        )
        assertEquals(2, info.peerCount)
    }

    @Test
    fun parse_readsCamelCaseAndStringIpv4Fallback() {
        val info = EasyTierRuntimeInfoParser.parse(
            rawJson = """
                {
                  "map": {
                    "runtime": {
                      "running": false,
                      "errorMessage": "dial failed",
                      "myNodeInfo": { "virtualIpv4": "10.0.0.9/24" },
                      "routes": []
                    }
                  }
                }
            """.trimIndent(),
            instanceName = "runtime",
        )

        requireNotNull(info)
        assertEquals(false, info.running)
        assertEquals("dial failed", info.errorMessage)
        assertEquals("10.0.0.0/24", info.routeCidrs.single())
    }
}
