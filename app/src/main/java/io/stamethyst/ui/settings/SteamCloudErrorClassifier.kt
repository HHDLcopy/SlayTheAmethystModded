package io.stamethyst.ui.settings

import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

internal enum class SteamCloudErrorKind {
    USER_CANCELLED,
    AUTH_CONNECTION_CANCELLED,
    AUTH_WATCHDOG_DISCONNECT,
    UPLOAD_DISCONNECT,
    OTHER,
}

internal object SteamCloudErrorClassifier {
    fun classify(error: Throwable): SteamCloudErrorKind {
        val causeChain = unwrapCauseChain(error).toList()
        if (causeChain.any(::isSteamCloudAuthConnectionCancellation)) {
            return SteamCloudErrorKind.AUTH_CONNECTION_CANCELLED
        }

        val message = firstMeaningfulMessage(causeChain)
        val firstCause = causeChain.firstOrNull()
        if (firstCause is CancellationException && isExplicitUserCancellation(firstCause)) {
            return SteamCloudErrorKind.USER_CANCELLED
        }
        if (firstCause is CancellationException) {
            return SteamCloudErrorKind.AUTH_CONNECTION_CANCELLED
        }
        if (isSteamCloudUploadDisconnect(message)) {
            return SteamCloudErrorKind.UPLOAD_DISCONNECT
        }
        if (isSteamCloudAuthWatchdogDisconnect(message)) {
            return SteamCloudErrorKind.AUTH_WATCHDOG_DISCONNECT
        }
        return SteamCloudErrorKind.OTHER
    }

    fun meaningfulCause(error: Throwable): Throwable {
        return unwrapCauseChain(error).firstOrNull { current ->
            current.message?.trim()?.isNotEmpty() == true
        } ?: unwrapCauseChain(error).first()
    }

    private fun unwrapCauseChain(error: Throwable): Sequence<Throwable> {
        return sequence {
            var current = unwrapAsyncThrowable(error)
            while (true) {
                yield(current)
                val next = current.cause?.takeUnless { it === current } ?: break
                current = unwrapAsyncThrowable(next)
            }
        }
    }

    private fun unwrapAsyncThrowable(error: Throwable): Throwable {
        var current = error
        while (true) {
            val cause = when (current) {
                is ExecutionException -> current.cause
                is CompletionException -> current.cause
                else -> null
            }
            if (cause == null || cause === current) {
                return current
            }
            current = cause
        }
    }

    private fun firstMeaningfulMessage(causeChain: List<Throwable>): String {
        return causeChain.firstOrNull { current ->
            current.message?.trim()?.isNotEmpty() == true
        }?.message?.trim().orEmpty()
    }

    private fun isSteamCloudAuthConnectionCancellation(error: Throwable): Boolean {
        if (error !is CancellationException) {
            return false
        }
        val normalized = error.message.orEmpty().lowercase(Locale.US)
        return normalized.contains("channel was cancelled") ||
            normalized.contains("channel was canceled")
    }

    private fun isExplicitUserCancellation(error: CancellationException): Boolean {
        val normalized = error.message.orEmpty().lowercase(Locale.US)
        return normalized.contains("cancelled by user") ||
            normalized.contains("canceled by user") ||
            normalized.contains("login restarted") ||
            normalized.contains("credentials cleared") ||
            normalized.contains("settings screen cleared")
    }

    private fun isSteamCloudAuthWatchdogDisconnect(message: String): Boolean {
        val normalized = message.lowercase(Locale.US)
        return normalized.contains("steam disconnected") &&
            normalized.contains("steam auth completion") &&
            normalized.contains("watchdog")
    }

    private fun isSteamCloudUploadDisconnect(message: String): Boolean {
        val normalized = message.lowercase(Locale.US)
        return normalized.contains("beginhttpupload") &&
            (normalized.contains("steam disconnected") ||
                normalized.contains("client or session is no longer active"))
    }
}
