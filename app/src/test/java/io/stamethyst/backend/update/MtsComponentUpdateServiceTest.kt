package io.stamethyst.backend.update

import io.stamethyst.backend.mods.MtsLoaderCrashPatcher
import java.io.File
import java.nio.file.Files
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MtsComponentUpdateServiceTest {
    @Test
    fun isMtsJarOutdated_matchesPinnedOutdatedJarHash() {
        val outdatedMts = requireOutdatedMtsFixtureOrSkip()

        assertTrue(MtsComponentUpdateService.isMtsJarOutdated(outdatedMts))
    }

    @Test
    fun isMtsJarOutdated_matchesLauncherPatchedOutdatedJar() {
        val outdatedMts = requireOutdatedMtsFixtureOrSkip()

        val tempJar = Files.createTempFile("outdated-mts-patched-", ".jar").toFile()
        try {
            outdatedMts.copyTo(tempJar, overwrite = true)
            MtsLoaderCrashPatcher.ensurePatchedMtsJar(tempJar)

            assertTrue(MtsComponentUpdateService.isMtsJarOutdated(tempJar))
        } finally {
            tempJar.delete()
        }
    }

    @Test
    fun isMtsJarOutdated_ignoresCurrentBundledJar() {
        assertFalse(MtsComponentUpdateService.isMtsJarOutdated(currentMtsFixture()))
    }

    private fun requireOutdatedMtsFixtureOrSkip(): File {
        val fixture = findFixture("ModTheSpire.jar.old")
        assumeTrue("Missing local old ModTheSpire.jar fixture", fixture != null)
        return fixture ?: error("Skipped when the old ModTheSpire.jar fixture is missing")
    }

    private fun currentMtsFixture(): File {
        return findFixture("ModTheSpire.jar")
            ?: error("Missing test fixture jar: ModTheSpire.jar")
    }

    private fun findFixture(name: String): File? {
        return sequenceOf(
            File("src/main/assets/components/mods/$name"),
            File("app/src/main/assets/components/mods/$name")
        ).firstOrNull { it.isFile }
    }
}
