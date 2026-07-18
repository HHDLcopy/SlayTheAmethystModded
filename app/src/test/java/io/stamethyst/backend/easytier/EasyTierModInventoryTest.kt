package io.stamethyst.backend.easytier

import io.stamethyst.backend.mods.ModManager
import io.stamethyst.backend.workshop.WorkshopInstalledModRecord
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class EasyTierModInventoryTest {
    @Test
    fun buildReportedMods_classifiesWorkshopAndLocalEnabledMods() {
        val workshopJar = File("/mods/together-in-spire.jar").absoluteFile
        val duplicateWorkshopJar = File("/mods/together-in-spire-copy.jar").absoluteFile
        val localJar = File("/mods/my-local-patch.jar").absoluteFile
        val disabledJar = File("/mods/disabled.jar").absoluteFile
        val workshopRecord = WorkshopInstalledModRecord(
            appId = 646570u,
            publishedFileId = 2384072973uL,
            title = "Together in Spire",
            description = "",
            previewUrl = "",
            versionText = "",
            updatedAtMillis = 0L,
            installedAtMillis = 0L,
            localJarPath = workshopJar.absolutePath,
        )

        val reported = EasyTierModInventory.buildReportedMods(
            installedMods = listOf(
                installedMod("Together in Spire", workshopJar),
                installedMod("Together in Spire duplicate", duplicateWorkshopJar),
                installedMod("My local patch", localJar),
                installedMod("Disabled mod", disabledJar, enabled = false),
                installedMod("Bundled launcher mod", File("/mods/bundled.jar"), required = true),
            ),
            workshopByJarPath = mapOf(
                workshopJar.absolutePath to workshopRecord,
                duplicateWorkshopJar.absolutePath to workshopRecord,
            ),
        )

        assertEquals(
            listOf(
                EasyTierRoomMod("Together in Spire", "2384072973"),
                EasyTierRoomMod("My local patch"),
            ),
            reported,
        )
    }

    private fun installedMod(
        name: String,
        jarFile: File,
        enabled: Boolean = true,
        required: Boolean = false,
    ): ModManager.InstalledMod = ModManager.InstalledMod(
        modId = name.lowercase().replace(' ', '-'),
        manifestModId = name.lowercase().replace(' ', '-'),
        name = name,
        version = "1.0",
        description = "",
        dependencies = emptyList(),
        jarFile = jarFile,
        required = required,
        installed = true,
        enabled = enabled,
        explicitPriority = null,
        effectivePriority = null,
    )
}
