package io.stamethyst.backend.mods.importing.patches

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportPatchModuleDefaultsTest {
    @Test
    fun basePlan_usesModuleDefaultEnablement() {
        val atlasDownscalePlan = AtlasOfflineDownscalePatchModule.basePlan(applicable = true)
        assertFalse(atlasDownscalePlan.defaultEnabled)
        assertTrue(atlasDownscalePlan.userConfigurable)

        val duplicateEntryPlan = DuplicateZipEntryPatchModule.basePlan(applicable = true)
        assertTrue(duplicateEntryPlan.defaultEnabled)
        assertFalse(duplicateEntryPlan.userConfigurable)
    }

    @Test
    fun atlasDownscale_isOnlyInteractivePatchModule() {
        val modules = listOf(
            DuplicateZipEntryPatchModule,
            ManifestRootPatchModule,
            AtlasFilterPatchModule,
            AtlasOfflineDownscalePatchModule,
            FrierenImportPatchModule,
            DownfallImportPatchModule,
            VupShionImportPatchModule,
            ChaofanModImportPatchModule,
            JacketNoAnoKoImportPatchModule,
            OriImportPatchModule,
        )

        assertEquals(
            listOf(AtlasOfflineDownscalePatchModule.id),
            modules.filter { it.userConfigurable }.map { it.id }
        )
    }

    @Test
    fun nonInteractiveModules_areEnabledByDefault() {
        val modules = listOf(
            DuplicateZipEntryPatchModule,
            ManifestRootPatchModule,
            AtlasFilterPatchModule,
            AtlasOfflineDownscalePatchModule,
            FrierenImportPatchModule,
            DownfallImportPatchModule,
            VupShionImportPatchModule,
            ChaofanModImportPatchModule,
            JacketNoAnoKoImportPatchModule,
            OriImportPatchModule,
        )

        assertTrue(modules.filterNot { it.userConfigurable }.all { it.defaultEnabled })
    }
}
