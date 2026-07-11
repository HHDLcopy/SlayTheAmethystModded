package io.stamethyst.backend.easytier

import org.json.JSONArray
import org.json.JSONObject

internal data class EasyTierRuntimeInfo(
    val instanceName: String,
    val running: Boolean,
    val errorMessage: String,
    val virtualIpv4Cidr: String,
    val routeCidrs: List<String>,
    val peerCount: Int?,
)

internal object EasyTierRuntimeInfoParser {
    fun parse(
        rawJson: String?,
        instanceName: String,
    ): EasyTierRuntimeInfo? {
        val root = rawJson?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val mapObject = runCatching { JSONObject(root).optJSONObjectCompat("map") }.getOrNull()
            ?: return null
        val instanceObject = mapObject.optJSONObjectCompat(instanceName)
            ?: mapObject.keys().asSequence()
                .mapNotNull { key -> mapObject.optJSONObjectCompat(key) }
                .firstOrNull()
            ?: return null

        val myNodeInfo = instanceObject.optJSONObjectCompat("my_node_info", "myNodeInfo")
        val virtualIpv4Cidr = parseIpv4Inet(
            myNodeInfo?.optAny("virtual_ipv4", "virtualIpv4")
        ).orEmpty()
        val routeCidrs = buildList {
            if (virtualIpv4Cidr.isNotBlank()) {
                normalizeEasyTierIpv4Route(virtualIpv4Cidr)?.cidr?.let(::add)
            }
            instanceObject.optJSONArrayCompat("routes")?.forEachObject { route ->
                route.optStringArray("proxy_cidrs", "proxyCidrs").forEach { cidr ->
                    normalizeEasyTierIpv4Route(cidr)?.cidr
                        ?.takeUnless(::isDefaultEasyTierIpv4Route)
                        ?.let(::add)
                }
            }
        }.distinct()

        return EasyTierRuntimeInfo(
            instanceName = instanceName,
            running = instanceObject.optBooleanCompat("running"),
            errorMessage = instanceObject.optStringCompat(
                "error_msg",
                "errorMsg",
                "error_message",
                "errorMessage",
            ),
            virtualIpv4Cidr = virtualIpv4Cidr,
            routeCidrs = routeCidrs,
            peerCount = instanceObject.optJSONArrayCompat("peers")?.length()
                ?: instanceObject.optJSONArrayCompat("routes")?.length(),
        )
    }

    private fun parseIpv4Inet(value: Any?): String? {
        return when (value) {
            is String -> parseEasyTierIpv4Cidr(value)?.cidr
            is JSONObject -> {
                val address = value.optAny("address")
                val networkLength = value.optIntCompat("network_length", "networkLength")
                    ?: value.optIntCompat("prefix_length", "prefixLength")
                    ?: 32
                val addressValue = when (address) {
                    is JSONObject -> address.optLongCompat("addr")
                    is Number -> address.toLong()
                    is String -> address.toLongOrNull()
                    else -> null
                } ?: return null
                formatEasyTierIpv4Inet(addressValue, networkLength)
            }
            else -> null
        }
    }

    private fun JSONObject.optJSONObjectCompat(vararg names: String): JSONObject? {
        for (name in names) {
            val value = opt(name)
            if (value is JSONObject) return value
        }
        return null
    }

    private fun JSONObject.optJSONArrayCompat(vararg names: String): JSONArray? {
        for (name in names) {
            val value = opt(name)
            if (value is JSONArray) return value
        }
        return null
    }

    private fun JSONObject.optAny(vararg names: String): Any? {
        for (name in names) {
            if (has(name)) {
                return opt(name)
            }
        }
        return null
    }

    private fun JSONObject.optStringCompat(vararg names: String): String {
        for (name in names) {
            val value = optString(name, "").trim()
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    private fun JSONObject.optBooleanCompat(name: String): Boolean {
        val value = opt(name)
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.equals("true", ignoreCase = true) || value == "1"
            else -> false
        }
    }

    private fun JSONObject.optIntCompat(vararg names: String): Int? {
        for (name in names) {
            if (!has(name)) continue
            val value = opt(name)
            val parsed = when (value) {
                is Number -> value.toInt()
                is String -> value.trim().toIntOrNull()
                else -> null
            }
            if (parsed != null) return parsed
        }
        return null
    }

    private fun JSONObject.optLongCompat(vararg names: String): Long? {
        for (name in names) {
            if (!has(name)) continue
            val value = opt(name)
            val parsed = when (value) {
                is Number -> value.toLong()
                is String -> value.trim().toLongOrNull()
                else -> null
            }
            if (parsed != null) return parsed
        }
        return null
    }

    private fun JSONObject.optStringArray(vararg names: String): List<String> {
        val array = optJSONArrayCompat(*names) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
            }
        }
    }

    private fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
        for (index in 0 until length()) {
            val value = opt(index)
            if (value is JSONObject) block(value)
        }
    }
}
