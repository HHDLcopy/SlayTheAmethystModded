package io.stamethyst.ui.settings

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import org.junit.Assert.assertEquals
import org.junit.Test

class SteamCloudErrorClassifierTest {
    @Test
    fun classify_distinguishesJavaSteamChannelCancellationFromUserCancellation() {
        val error = CompletionException(CancellationException("Channel was cancelled"))

        assertEquals(
            SteamCloudErrorKind.AUTH_CONNECTION_CANCELLED,
            SteamCloudErrorClassifier.classify(error)
        )
    }

    @Test
    fun classify_keepsExplicitUserCancellationAsUserCancelled() {
        val error = CancellationException("Steam Cloud login cancelled by user.")

        assertEquals(
            SteamCloudErrorKind.USER_CANCELLED,
            SteamCloudErrorClassifier.classify(error)
        )
    }

    @Test
    fun classify_keepsWrappedExplicitUserCancellationAsUserCancelled() {
        val error = CompletionException(CancellationException("Steam Cloud login cancelled by user."))

        assertEquals(
            SteamCloudErrorKind.USER_CANCELLED,
            SteamCloudErrorClassifier.classify(error)
        )
    }

    @Test
    fun classify_treatsBlankCancellationAsAuthConnectionCancellation() {
        val error = CancellationException()

        assertEquals(
            SteamCloudErrorKind.AUTH_CONNECTION_CANCELLED,
            SteamCloudErrorClassifier.classify(error)
        )
    }

    @Test
    fun classify_treatsSteamInvalidPasswordAsInvalidCredentials() {
        val error = CompletionException(
            RuntimeException("Authentication failed via credentials with result: InvalidPassword")
        )

        assertEquals(
            SteamCloudErrorKind.INVALID_CREDENTIALS,
            SteamCloudErrorClassifier.classify(error)
        )
    }

    @Test
    fun classify_treatsProtocolInvalidPasswordResultAsInvalidCredentials() {
        val error = CompletionException(
            RuntimeException("Steam 登录失败: 账号名或密码错误 (EResult=5)")
        )

        assertEquals(
            SteamCloudErrorKind.INVALID_CREDENTIALS,
            SteamCloudErrorClassifier.classify(error)
        )
    }

    @Test
    fun classify_treatsProtocolAuthCompletionTimeoutAsGuardWaitTimeout() {
        val error = CompletionException(
            RuntimeException("Timed out waiting for Steam auth completion after 240000ms.")
        )

        assertEquals(
            SteamCloudErrorKind.AUTH_WATCHDOG_DISCONNECT,
            SteamCloudErrorClassifier.classify(error)
        )
    }
}
