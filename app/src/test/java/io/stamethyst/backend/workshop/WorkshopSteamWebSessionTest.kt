package io.stamethyst.backend.workshop

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkshopSteamWebSessionTest {
    @Test
    fun expirationMillisOrNull_readsJwtExpiration() {
        val token = jwtWithPayload("{\"sub\":\"76561198000000000\",\"exp\":1900000000}")

        assertEquals(1_900_000_000_000L, token.expirationMillisOrNull())
    }

    @Test
    fun expirationMillisOrNull_rejectsMissingOrInvalidExpiration() {
        assertNull(jwtWithPayload("{\"sub\":\"76561198000000000\"}").expirationMillisOrNull())
        assertNull(jwtWithPayload("{\"exp\":0}").expirationMillisOrNull())
        assertNull("not-a-jwt".expirationMillisOrNull())
    }

    private fun jwtWithPayload(payload: String): String = listOf(
        Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".toByteArray()),
        Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()),
        "signature",
    ).joinToString(".")
}
