package io.stamethyst.input

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemKeyboardPreviewRequestTest {
    @Test
    fun parse_decodesInitialTextFilterAndLimit() {
        val request = SystemKeyboardPreviewRequest.parse(
            buildPayload(
                initialText = "10.144.0.1",
                allowedCharacters = "0123456789.",
                characterLimit = 15,
            )
        )

        assertEquals("10.144.0.1", request?.initialText)
        assertEquals("0123456789.".toSet(), request?.allowedCharacters)
        assertEquals(15, request?.characterLimit)
    }

    @Test
    fun parse_treatsEmptyFilterAndNonPositiveLimitAsUnrestricted() {
        val request = SystemKeyboardPreviewRequest.parse(
            buildPayload(
                initialText = "联机房间",
                allowedCharacters = "",
                characterLimit = -1,
            )
        )

        assertEquals("联机房间", request?.initialText)
        assertNull(request?.allowedCharacters)
        assertNull(request?.characterLimit)
    }

    @Test
    fun parse_rejectsMalformedOrUnrelatedPayloads() {
        assertNull(SystemKeyboardPreviewRequest.parse("system_keyboard:floating_tools\n1"))
        assertNull(
            SystemKeyboardPreviewRequest.parse(
                "system_keyboard_preview:together_in_spire\n1\n%%%\n\n-1"
            )
        )
    }

    private fun buildPayload(
        initialText: String,
        allowedCharacters: String,
        characterLimit: Int,
    ): String {
        return listOf(
            "system_keyboard_preview:together_in_spire",
            "123456789",
            encode(initialText),
            encode(allowedCharacters),
            characterLimit.toString(),
        ).joinToString("\n")
    }

    private fun encode(value: String): String {
        return Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    }
}
