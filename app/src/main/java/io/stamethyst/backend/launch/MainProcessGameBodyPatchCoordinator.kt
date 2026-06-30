package io.stamethyst.backend.launch

import android.content.Context
import io.stamethyst.backend.diag.MemoryDiagnosticsLogger
import io.stamethyst.backend.mods.ModClasspathJarBuilder
import io.stamethyst.backend.mods.StsDesktopJarPatcher
import io.stamethyst.backend.process.AppProcess
import io.stamethyst.config.RuntimePaths
import java.io.IOException

object MainProcessGameBodyPatchCoordinator {
    private const val COMPONENT_PREPARE_END_PERCENT = 40
    private const val DESKTOP_PATCH_START_PERCENT = 42

    @JvmStatic
    @Throws(IOException::class)
    fun prepareBeforeLaunch(
        context: Context,
        launchMode: String,
        progressCallback: StartupProgressCallback? = null,
        assumeComponentInstallComplete: Boolean = false
    ) {
        if (!StsLaunchSpec.isMtsLaunchMode(launchMode)) {
            return
        }
        val appContext = context.applicationContext
        if (!AppProcess.isDefaultProcess(appContext)) {
            throw IOException("Game body patching must run in the main process before launch")
        }

        val stsJar = RuntimePaths.importedStsJar(appContext)
        val patchJar = RuntimePaths.gdxPatchJar(appContext)
        val packagedComponentsCurrent = ComponentInstaller.arePackagedComponentsCurrent(appContext)
        val alreadyPatchedBeforeInstall = packagedComponentsCurrent &&
            StsDesktopJarPatcher.isPatchedWithCurrentPatch(stsJar, patchJar)
        val visibleProgressCallback = if (alreadyPatchedBeforeInstall) {
            null
        } else {
            progressCallback
        }

        if (!assumeComponentInstallComplete) {
            ComponentInstaller.ensureInstalled(
                context = appContext,
                progressCallback = mapProgressRange(
                    visibleProgressCallback,
                    0,
                    COMPONENT_PREPARE_END_PERCENT
                )
            )
        }

        val alreadyPatched = StsDesktopJarPatcher.isPatchedWithCurrentPatch(stsJar, patchJar)
        MemoryDiagnosticsLogger.logEvent(
            context = appContext,
            event = "main_process_game_body_patch_started",
            extras = linkedMapOf<String, Any?>(
                "launchMode" to launchMode,
                "alreadyPatched" to alreadyPatched
            ),
            includeMemorySnapshot = false
        )

        if (alreadyPatched) {
            MemoryDiagnosticsLogger.logEvent(
                context = appContext,
                event = "main_process_game_body_patch_skipped",
                extras = linkedMapOf<String, Any?>("launchMode" to launchMode),
                includeMemorySnapshot = false
            )
            return
        }

        StsDesktopJarPatcher.ensurePatchedStsJar(
            context = appContext,
            stsJar = stsJar,
            patchJar = patchJar,
            progressCallback = buildPatchProgressCallback(
                mapProgressRange(progressCallback, DESKTOP_PATCH_START_PERCENT, 100)
            )
        )
        MtsStartupCacheCoordinator.invalidate(appContext)
        MemoryDiagnosticsLogger.logEvent(
            context = appContext,
            event = "main_process_game_body_patch_completed",
            extras = linkedMapOf<String, Any?>("launchMode" to launchMode),
            includeMemorySnapshot = false
        )
    }

    private fun buildPatchProgressCallback(
        progressCallback: StartupProgressCallback?
    ): ModClasspathJarBuilder.BuildProgressCallback? {
        if (progressCallback == null) {
            return null
        }
        return ModClasspathJarBuilder.BuildProgressCallback { percent, message ->
            progressCallback.onProgress(percent, message)
        }
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
