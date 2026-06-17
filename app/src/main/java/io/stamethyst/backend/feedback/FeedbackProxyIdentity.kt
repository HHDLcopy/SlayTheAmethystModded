package io.stamethyst.backend.feedback

import android.os.Build
import java.util.Locale

internal const val FEEDBACK_PROXY_REPORTER_LOGIN = "defect-reporter"
private const val FEEDBACK_PROXY_REPORTER_BOT_LOGIN = "defect-reporter[bot]"

internal data class FeedbackProxyFooter(
    val playerName: String,
    val deviceLabel: String
)

private val feedbackProxyCommentRegex =
    Regex("""<!--\s*sts-feedback-proxy:(\{.*?\})\s*-->""", setOf(RegexOption.DOT_MATCHES_ALL))
private val feedbackProxyFooterRegex =
    Regex("""(?s)(?:\r?\n){0,2}\s*---\s*\r?\n\s*由\s+SlayTheAmethyst\s+启动器代发.*$""")
private val feedbackProxyFooterMarkerRegex =
    Regex("""^由\s+SlayTheAmethyst\s+启动器代发\s*$""")
private val feedbackProxyFooterFieldRegex =
    Regex("""^\s*[-*+]\s*([^：:]+?)\s*[：:]\s*(.*?)\s*$""")
private val feedbackIdentityWhitespaceRegex = Regex("""\s+""")

internal fun buildFeedbackDeviceLabel(): String {
    return buildString {
        append(Build.MANUFACTURER.orEmpty().trim())
        if (!Build.MODEL.isNullOrBlank()) {
            append(' ')
            append(Build.MODEL.trim())
        }
    }.trim().ifEmpty { "Android Device" }
}

internal fun buildFeedbackProxyAuthorIdentity(
    playerName: String,
    deviceLabel: String
): String {
    val normalizedPlayerName = normalizeFeedbackIdentityPart(playerName)
        .ifBlank { "player" }
    val normalizedDeviceLabel = normalizeFeedbackIdentityPart(deviceLabel)
        .ifBlank { "android device" }
    return "$normalizedPlayerName\u001F$normalizedDeviceLabel"
}

internal fun isFeedbackProxyReporterLogin(login: String): Boolean {
    return when (login.trim().lowercase(Locale.ROOT)) {
        FEEDBACK_PROXY_REPORTER_LOGIN,
        FEEDBACK_PROXY_REPORTER_BOT_LOGIN -> true
        else -> false
    }
}

internal fun extractFeedbackProxyPayloadJson(rawBody: String): String? {
    val match = feedbackProxyCommentRegex.find(rawBody) ?: return null
    return match.groupValues.getOrNull(1)?.trim()?.takeIf(String::isNotEmpty)
}

internal fun parseFeedbackProxyFooter(rawBody: String): FeedbackProxyFooter? {
    val lines = rawBody
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lines()
    val markerIndex = lines.indexOfFirst { line ->
        feedbackProxyFooterMarkerRegex.matches(line.trim())
    }
    if (markerIndex < 0) {
        return null
    }

    var playerName = ""
    var deviceLabel = ""
    for (index in markerIndex + 1 until lines.size) {
        val line = lines[index].trim()
        if (line.startsWith("<!--")) {
            break
        }
        val match = feedbackProxyFooterFieldRegex.matchEntire(line) ?: continue
        val key = match.groupValues[1].trim()
        val value = match.groupValues[2].trim()
        when (key) {
            "玩家名" -> playerName = value
            "设备" -> deviceLabel = value
        }
    }
    if (playerName.isBlank() || deviceLabel.isBlank()) {
        return null
    }
    return FeedbackProxyFooter(
        playerName = playerName,
        deviceLabel = deviceLabel
    )
}

internal fun stripFeedbackProxyMetadataForDisplay(rawBody: String): String {
    return rawBody
        .replace(feedbackProxyCommentRegex, "")
        .replace(feedbackProxyFooterRegex, "")
        .trim()
}

private fun normalizeFeedbackIdentityPart(value: String): String {
    return value
        .trim()
        .replace(feedbackIdentityWhitespaceRegex, " ")
        .lowercase(Locale.ROOT)
}
