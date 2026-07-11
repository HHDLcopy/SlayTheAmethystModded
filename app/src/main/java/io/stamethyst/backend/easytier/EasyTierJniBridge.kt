package io.stamethyst.backend.easytier

import com.easytier.jni.EasyTierJNI

internal object EasyTierJniBridge {
    private const val NATIVE_LIBRARY_NOT_BUNDLED =
        "EasyTier native runtime library is not bundled in this build yet."
    private const val NATIVE_LIBRARY_LOAD_FAILED =
        "EasyTier native runtime failed to load."

    fun parseConfig(config: String): Result<Unit> = callNative {
        val result = EasyTierJNI.parseConfig(config)
        if (result != 0) {
            throw IllegalStateException(lastError("EasyTier config parse failed."))
        }
    }

    fun runNetworkInstance(config: String): Result<Unit> = callNative {
        val result = EasyTierJNI.runNetworkInstance(config)
        if (result != 0) {
            throw IllegalStateException(lastError("EasyTier network instance failed to start."))
        }
    }

    fun setTunFd(instanceName: String, fd: Int): Result<Unit> = callNative {
        val result = EasyTierJNI.setTunFd(instanceName, fd)
        if (result != 0) {
            throw IllegalStateException(lastError("EasyTier rejected the Android TUN file descriptor."))
        }
    }

    fun stopAllInstances(): Result<Unit> = callNative {
        val result = EasyTierJNI.stopAllInstances()
        if (result != 0) {
            throw IllegalStateException(lastError("EasyTier failed to stop running instances."))
        }
    }

    fun collectNetworkInfo(instanceName: String): Result<EasyTierRuntimeInfo?> = callNative {
        EasyTierRuntimeInfoParser.parse(
            rawJson = EasyTierJNI.collectNetworkInfos(10),
            instanceName = instanceName,
        )
    }

    fun failureSummary(error: Throwable): String {
        findNativeLinkageError(error)?.let { nativeError ->
            if (isNativeLibraryMissing(nativeError)) {
                return NATIVE_LIBRARY_NOT_BUNDLED
            }
            val message = nativeError.message?.trim().orEmpty()
            return if (message.isBlank()) {
                NATIVE_LIBRARY_LOAD_FAILED
            } else {
                "$NATIVE_LIBRARY_LOAD_FAILED $message"
            }
        }
        return error.message?.trim().takeUnless { it.isNullOrEmpty() }
            ?: error.javaClass.simpleName
    }

    fun failureCategory(error: Throwable): EasyTierFailureCategory {
        return if (findNativeLinkageError(error) != null) {
            EasyTierFailureCategory.RuntimeBridgeUnavailable
        } else {
            EasyTierErrorClassifier.classifyFromSummary(failureSummary(error))
                .takeUnless { it == EasyTierFailureCategory.None }
                ?: EasyTierFailureCategory.Unknown
        }
    }

    private fun <T> callNative(block: () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun lastError(fallback: String): String =
        runCatching { EasyTierJNI.getLastError()?.trim().orEmpty() }
            .getOrDefault("")
            .ifBlank { fallback }

    private fun findNativeLinkageError(error: Throwable): Throwable? {
        var cursor: Throwable? = error
        while (cursor != null) {
            if (cursor is UnsatisfiedLinkError || cursor is NoClassDefFoundError) {
                return cursor
            }
            cursor = cursor.cause
        }
        return null
    }

    private fun isNativeLibraryMissing(error: Throwable): Boolean {
        val message = error.message
            ?.trim()
            ?.lowercase()
            .orEmpty()
        return message.contains("couldn't find") ||
            message.contains("could not find") ||
            (
                message.contains("not found") &&
                    message.contains("libeasytier") &&
                    !message.contains("cannot locate symbol")
                )
    }
}

internal sealed class EasyTierRuntimeStartResult {
    data class Started(
        val config: EasyTierRuntimeConfig,
    ) : EasyTierRuntimeStartResult()

    data class Failed(
        val summary: String,
        val failureCategory: EasyTierFailureCategory,
        val error: Throwable?,
    ) : EasyTierRuntimeStartResult()
}

internal object EasyTierRuntimeBridge {
    fun startNetworkInstance(
        sessionConfig: EasyTierRoomSessionConfig,
        playerId: String,
    ): EasyTierRuntimeStartResult {
        if (sessionConfig.networkSecret.isBlank()) {
            return EasyTierRuntimeStartResult.Failed(
                summary = "EasyTier room session did not include a network secret.",
                failureCategory = EasyTierFailureCategory.ConfigMissing,
                error = null,
            )
        }
        val runtimeConfig = EasyTierRuntimeConfigBuilder.build(sessionConfig, playerId)
        if (runtimeConfig.peerUrls.isEmpty()) {
            return EasyTierRuntimeStartResult.Failed(
                summary = "EasyTier room session did not include an entry node URL.",
                failureCategory = EasyTierFailureCategory.ConfigMissing,
                error = null,
            )
        }

        EasyTierJniBridge.parseConfig(runtimeConfig.toml).exceptionOrNull()?.let { error ->
            return error.toStartFailure()
        }
        EasyTierJniBridge.runNetworkInstance(runtimeConfig.toml).exceptionOrNull()?.let { error ->
            return error.toStartFailure()
        }

        return EasyTierRuntimeStartResult.Started(runtimeConfig)
    }

    private fun Throwable.toStartFailure(): EasyTierRuntimeStartResult.Failed =
        EasyTierRuntimeStartResult.Failed(
            summary = EasyTierJniBridge.failureSummary(this),
            failureCategory = EasyTierJniBridge.failureCategory(this),
            error = this,
        )
}
