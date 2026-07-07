package io.stamethyst.backend.mods.importing.patches

import android.content.Context
import io.stamethyst.backend.mods.importing.patches.mods.chaofanmod.ChaofanModImportPatchModule
import io.stamethyst.backend.mods.importing.patches.mods.downfall.DownfallImportPatchModule
import io.stamethyst.backend.mods.importing.patches.mods.frieren.FrierenImportPatchModule
import io.stamethyst.backend.mods.importing.patches.mods.jacketnoanoko.JacketNoAnoKoImportPatchModule
import io.stamethyst.backend.mods.importing.patches.mods.ori.OriImportPatchModule
import io.stamethyst.backend.mods.importing.patches.mods.vupshion.VupShionImportPatchModule
import io.stamethyst.backend.mods.importing.patches.structure.DuplicateZipEntryPatchModule
import io.stamethyst.backend.mods.importing.patches.structure.ManifestRootPatchModule
import io.stamethyst.backend.mods.importing.patches.texture.AtlasFilterPatchModule
import io.stamethyst.backend.mods.importing.patches.texture.AtlasOfflineDownscalePatchModule

internal object ImportPatchRegistry {
    private val registrations: List<ImportPatchModule> = listOf(
        DuplicateZipEntryPatchModule,
        ManifestRootPatchModule,
        AtlasFilterPatchModule,
        AtlasOfflineDownscalePatchModule,
        JacketNoAnoKoImportPatchModule,
        FrierenImportPatchModule,
        DownfallImportPatchModule,
        VupShionImportPatchModule,
        ChaofanModImportPatchModule,
        OriImportPatchModule
    )

    fun modules(context: Context): List<ImportPatchModule> {
        return registrations
            .filter { it.isAvailable(context) }
            .sortedBy { it.order }
    }
}
