package io.stamethyst.backend.launch

import android.content.Context
import io.stamethyst.backend.process.AppProcess
import java.io.IOException

object MainProcessLaunchPreparationCoordinator {
    private const val COMMON_PREPARATION_END_PERCENT = 60
    private const val BODY_PATCH_END_PERCENT = 82
    private const val MTS_WARMUP_START_PERCENT = 83

    @JvmStatic
    @Throws(IOException::class)
    fun prepareBeforeLaunch(
        context: Context,
        launchMode: String,
        progressCallback: StartupProgressCallback? = null
    ) {
        val appContext = context.applicationContext
        if (!AppProcess.isDefaultProcess(appContext)) {
            throw IOException("Game launch preparation must run in the main process before game launch")
        }

        if (StsLaunchSpec.isMtsLaunchMode(launchMode)) {
            LaunchPreparationService.prepare(
                context = appContext,
                launchMode = launchMode,
                progressCallback = mapProgressRange(
                    progressCallback,
                    0,
                    COMMON_PREPARATION_END_PERCENT
                )
            )
            MainProcessGameBodyPatchCoordinator.prepareBeforeLaunch(
                context = appContext,
                launchMode = launchMode,
                progressCallback = mapProgressRange(
                    progressCallback,
                    COMMON_PREPARATION_END_PERCENT + 1,
                    BODY_PATCH_END_PERCENT
                ),
                assumeComponentInstallComplete = true
            )
            MtsClasspathWarmupCoordinator.prepareForLaunch(
                context = appContext,
                progressCallback = mapProgressRange(
                    progressCallback,
                    MTS_WARMUP_START_PERCENT,
                    100
                ),
                assumeCommonPreparationDone = true
            )
            return
        }

        LaunchPreparationService.prepare(
            context = appContext,
            launchMode = launchMode,
            progressCallback = progressCallback
        )
    }

    private fun mapProgressRange(
        callback: StartupProgressCallback?,
        startPercent: Int,
        endPercent: Int
    ): StartupProgressCallback? {
        if (callback == null) {
            return null
        }
        val safeStart = startPercent.coerceIn(0, 100)
        val safeEnd = endPercent.coerceIn(0, 100)
        return StartupProgressCallback { percent, message ->
            val bounded = percent.coerceIn(0, 100)
            val mapped = safeStart + (((safeEnd - safeStart) * bounded) / 100f).toInt()
            callback.onProgress(mapped.coerceIn(0, 100), message)
        }
    }
}
