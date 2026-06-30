package io.stamethyst.backend.launch

import android.content.Context
import io.stamethyst.backend.process.AppProcess
import java.io.IOException

object MainProcessMtsLaunchPreparationCoordinator {
    private const val BODY_PATCH_END_PERCENT = 45
    private const val WARMUP_START_PERCENT = 46

    @JvmStatic
    @Throws(IOException::class)
    fun prepareBeforeLaunch(
        context: Context,
        launchMode: String,
        progressCallback: StartupProgressCallback? = null
    ) {
        if (!StsLaunchSpec.isMtsLaunchMode(launchMode)) {
            return
        }
        val appContext = context.applicationContext
        if (!AppProcess.isDefaultProcess(appContext)) {
            throw IOException("MTS launch warmup must run in the main process before game launch")
        }

        MainProcessGameBodyPatchCoordinator.prepareBeforeLaunch(
            context = appContext,
            launchMode = launchMode,
            progressCallback = mapProgressRange(
                progressCallback,
                0,
                BODY_PATCH_END_PERCENT
            )
        )
        MtsClasspathWarmupCoordinator.prepareForLaunch(
            context = appContext,
            progressCallback = mapProgressRange(
                progressCallback,
                WARMUP_START_PERCENT,
                100
            )
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
