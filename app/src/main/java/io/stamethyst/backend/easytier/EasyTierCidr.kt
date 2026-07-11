package io.stamethyst.backend.easytier

import java.util.Locale

internal data class EasyTierIpv4Cidr(
    val address: String,
    val prefixLength: Int,
) {
    val cidr: String
        get() = "$address/$prefixLength"
}

internal fun parseEasyTierIpv4Cidr(value: String): EasyTierIpv4Cidr? {
    val normalized = value.trim()
    if (normalized.isEmpty()) return null
    val parts = normalized.split('/', limit = 2)
    val ip = parts.getOrNull(0)?.trim().orEmpty()
    val prefix = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 32
    if (prefix !in 0..32) return null
    val ipLong = parseIpv4AddressToLong(ip) ?: return null
    return EasyTierIpv4Cidr(formatIpv4(ipLong), prefix)
}

internal fun normalizeEasyTierIpv4Route(value: String): EasyTierIpv4Cidr? {
    val parsed = parseEasyTierIpv4Cidr(value) ?: return null
    val mask = ipv4PrefixMask(parsed.prefixLength)
    val network = parseIpv4AddressToLong(parsed.address)?.and(mask) ?: return null
    return EasyTierIpv4Cidr(formatIpv4(network), parsed.prefixLength)
}

internal fun isDefaultEasyTierIpv4Route(value: String): Boolean {
    val parsed = normalizeEasyTierIpv4Route(value) ?: return false
    return parsed.address == "0.0.0.0" && parsed.prefixLength == 0
}

internal fun formatEasyTierIpv4Inet(addressValue: Long, networkLength: Int): String? {
    if (networkLength !in 0..32) return null
    val normalized = addressValue and 0xffffffffL
    return "${formatIpv4(normalized)}/$networkLength"
}

private fun parseIpv4AddressToLong(value: String): Long? {
    val pieces = value.trim().lowercase(Locale.US).split('.')
    if (pieces.size != 4) return null
    var result = 0L
    for (piece in pieces) {
        val part = piece.toIntOrNull() ?: return null
        if (part !in 0..255) return null
        result = (result shl 8) or part.toLong()
    }
    return result
}

private fun formatIpv4(value: Long): String =
    listOf(
        (value ushr 24) and 0xff,
        (value ushr 16) and 0xff,
        (value ushr 8) and 0xff,
        value and 0xff,
    ).joinToString(".")

private fun ipv4PrefixMask(prefixLength: Int): Long {
    if (prefixLength <= 0) return 0L
    return (0xffffffffL shl (32 - prefixLength)) and 0xffffffffL
}
