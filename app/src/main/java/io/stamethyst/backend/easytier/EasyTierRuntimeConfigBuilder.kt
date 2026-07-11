package io.stamethyst.backend.easytier

import java.security.MessageDigest
import java.util.Locale

internal data class EasyTierRuntimeConfig(
    val instanceName: String,
    val networkName: String,
    val peerUrls: List<String>,
    val toml: String,
)

internal object EasyTierRuntimeConfigBuilder {
    fun build(
        sessionConfig: EasyTierRoomSessionConfig,
        playerId: String,
    ): EasyTierRuntimeConfig {
        val instanceName = buildInstanceName(sessionConfig.sessionId)
        val networkName = buildNetworkName(sessionConfig.roomId)
        val peerUrls = listOf(sessionConfig.entryNodeUrl)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        val hostname = buildHostname(playerId)

        val toml = buildString {
            append("instance_name = ").appendTomlString(instanceName).append('\n')
            append("hostname = ").appendTomlString(hostname).append('\n')
            append("dhcp = true\n")
            append("listeners = []\n")
            append('\n')
            append("[network_identity]\n")
            append("network_name = ").appendTomlString(networkName).append('\n')
            append("network_secret = ").appendTomlString(sessionConfig.networkSecret).append('\n')
            append('\n')
            peerUrls.forEach { peerUrl ->
                append("[[peer]]\n")
                append("uri = ").appendTomlString(peerUrl).append('\n')
                append('\n')
            }
        }

        return EasyTierRuntimeConfig(
            instanceName = instanceName,
            networkName = networkName,
            peerUrls = peerUrls,
            toml = toml,
        )
    }

    fun buildNetworkName(roomId: String): String {
        return stableName(
            prefix = "sts",
            value = roomId,
            fallback = "default-room",
            maxLength = 96,
            hashLength = 12,
        )
    }

    private fun buildInstanceName(sessionId: String): String {
        return stableName(
            prefix = "sts-android",
            value = sessionId,
            fallback = "session",
            maxLength = 96,
            hashLength = 12,
        )
    }

    private fun buildHostname(playerId: String): String {
        return stableName(
            prefix = "sts",
            value = playerId,
            fallback = "android",
            maxLength = 63,
            hashLength = 8,
        )
    }

    private fun slug(value: String): String =
        value.trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .replace(Regex("(^-+|-+$)"), "")

    private fun stableName(
        prefix: String,
        value: String,
        fallback: String,
        maxLength: Int,
        hashLength: Int,
    ): String {
        val hashInput = value.trim().ifBlank { fallback }
        val hash = sha256Hex(hashInput).take(hashLength)
        val bodyMaxLength = (maxLength - prefix.length - hash.length - 2).coerceAtLeast(1)
        val body = slug(value)
            .ifBlank { fallback }
            .take(bodyMaxLength)
            .trimEnd('-')
            .ifBlank { fallback.take(bodyMaxLength).trimEnd('-') }
        return "$prefix-$body-$hash"
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun StringBuilder.appendTomlString(value: String): StringBuilder {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
        return this
    }
}
