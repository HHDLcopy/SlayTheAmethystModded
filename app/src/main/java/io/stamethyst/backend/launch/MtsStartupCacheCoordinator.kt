package io.stamethyst.backend.launch

import android.content.Context

internal object MtsStartupCacheCoordinator {
    @JvmStatic
    fun invalidate(context: Context) {
        MtsClasspathWarmupCoordinator.invalidateCache(context)
        MtsPatchCacheCoordinator.invalidate(context)
    }
}
