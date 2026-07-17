package io.stamethyst.input

import java.nio.charset.StandardCharsets
import java.util.Base64

internal data class SystemKeyboardPreviewRequest(
    val initialText: String,
    val allowedCharacters: Set<Char>?,
    val characterLimit: Int?,
) {
    companion object {
        private const val SOURCE_PREFIX = "system_keyboard_preview:"

        fun parse(payload: String): SystemKeyboardPreviewRequest? {
            val lines = payload.lineSequence().map(String::trim).toList()
            if (!lines.firstOrNull().orEmpty().startsWith(SOURCE_PREFIX)) {
                return null
            }
            return try {
                val initialText = decode(lines.getOrNull(2).orEmpty())
                val allowedText = decode(lines.getOrNull(3).orEmpty())
                val characterLimit = lines.getOrNull(4)
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 }
                SystemKeyboardPreviewRequest(
                    initialText = initialText,
                    allowedCharacters = allowedText.takeIf(String::isNotEmpty)?.toSet(),
                    characterLimit = characterLimit,
                )
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun decode(value: String): String {
            if (value.isEmpty()) {
                return ""
            }
            return String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)
        }
    }
}
