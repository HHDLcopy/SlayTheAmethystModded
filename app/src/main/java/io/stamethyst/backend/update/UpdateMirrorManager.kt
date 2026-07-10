package io.stamethyst.backend.update

import android.content.Context
import io.stamethyst.backend.network.NetworkAccelerationPolicy
import io.stamethyst.ui.preferences.LauncherPreferences

object UpdateMirrorManager {
    fun current(context: Context): UpdateSource {
        if (NetworkAccelerationPolicy.shouldBypassAcceleratedLinks(context)) {
            return UpdateSource.OFFICIAL
        }
        return UpdateSource.normalizePreferredUserSource(
            LauncherPreferences.readPreferredUpdateMirrorId(context)
        )
    }

    fun selectableSources(context: Context): List<UpdateSource> {
        if (NetworkAccelerationPolicy.shouldBypassAcceleratedLinks(context)) {
            return listOf(UpdateSource.OFFICIAL)
        }
        return selectableSources()
    }

    fun selectableSources(): List<UpdateSource> {
        return UpdateSource.userSelectableSources()
    }

    fun saveCurrent(context: Context, source: UpdateSource) {
        if (!source.userSelectable) {
            return
        }
        if (NetworkAccelerationPolicy.shouldBypassAcceleratedLinks(context) && source != UpdateSource.OFFICIAL) {
            return
        }
        LauncherPreferences.savePreferredUpdateMirrorId(context, source.id)
    }

    fun displayNameOf(sourceId: String?): String? {
        return UpdateSource.fromPersistedValue(sourceId)?.displayName
    }
}
