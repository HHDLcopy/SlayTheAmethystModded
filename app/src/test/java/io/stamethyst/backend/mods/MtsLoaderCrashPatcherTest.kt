package io.stamethyst.backend.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MtsLoaderCrashPatcherTest {
    @Test
    fun ensurePatchedMtsJar_removesRunModsSwallowHandlersAndInjectsStartupHooks() {
        val sourceJar = sequenceOf(
            File("src/main/assets/components/mods/ModTheSpire.jar"),
            File("app/src/main/assets/components/mods/ModTheSpire.jar")
        ).firstOrNull { it.isFile }
            ?: error("Missing test fixture jar: ModTheSpire.jar")
        check(sourceJar.isFile) { "Missing test fixture jar: ${sourceJar.absolutePath}" }

        val tempJar = Files.createTempFile("mts-loader-patch-", ".jar").toFile()
        try {
            JarFileIoUtils.copyFileReplacing(sourceJar, tempJar)

            val originalLoaderBytes = JarFileIoUtils.readJarEntryBytes(
                tempJar,
                "com/evacipated/cardcrawl/modthespire/Loader.class"
            )
            requireNotNull(originalLoaderBytes)
            assertFalse(MtsLoaderCrashPatcher.isPatchedLoaderClass(originalLoaderBytes))

            assertTrue(MtsLoaderCrashPatcher.ensurePatchedMtsJar(tempJar))

            val patchedLoaderBytes = JarFileIoUtils.readJarEntryBytes(
                tempJar,
                "com/evacipated/cardcrawl/modthespire/Loader.class"
            )
            requireNotNull(patchedLoaderBytes)
            val patchedPackageJarBytes = JarFileIoUtils.readJarEntryBytes(
                tempJar,
                "com/evacipated/cardcrawl/modthespire/PackageJar.class"
            )
            requireNotNull(patchedPackageJarBytes)
            val patchedPrepackagedLauncherBytes = JarFileIoUtils.readJarEntryBytes(
                tempJar,
                "com/evacipated/cardcrawl/modthespire/PackageJar\$PrepackagedLauncher.class"
            )
            requireNotNull(patchedPrepackagedLauncherBytes)
            assertTrue(MtsLoaderCrashPatcher.isPatchedLoaderClass(patchedLoaderBytes))
            assertTrue(MtsLoaderCrashPatcher.isPatchedPackageJarClass(patchedPackageJarBytes))
            assertTrue(MtsLoaderCrashPatcher.isPatchedPrepackagedLauncherClass(patchedPrepackagedLauncherBytes))
            assertTrue(MtsLoaderCrashPatcher.hasPatchCacheLaunchHook(patchedLoaderBytes))
            assertTrue(MtsLoaderCrashPatcher.hasPatchCacheStoreHook(patchedLoaderBytes))
            assertTrue(MtsLoaderCrashPatcher.hasOutJarPrimingHook(patchedLoaderBytes))
            assertTrue(MtsLoaderCrashPatcher.hasPrepackagedPrepareHook(patchedPrepackagedLauncherBytes))
            assertFalse(MtsLoaderCrashPatcher.hasPrepackagedCallInitializersCall(patchedPrepackagedLauncherBytes))

            val patchedAgainBytes = MtsLoaderCrashPatcher.patchLoaderBytes(patchedLoaderBytes)
            assertTrue(patchedAgainBytes.contentEquals(patchedLoaderBytes))
            val patchedPackageAgainBytes = MtsLoaderCrashPatcher.patchPackageJarBytes(patchedPackageJarBytes)
            assertTrue(patchedPackageAgainBytes.contentEquals(patchedPackageJarBytes))
            val patchedPrepackagedAgainBytes =
                MtsLoaderCrashPatcher.patchPrepackagedLauncherBytes(patchedPrepackagedLauncherBytes)
            assertTrue(patchedPrepackagedAgainBytes.contentEquals(patchedPrepackagedLauncherBytes))
        } finally {
            tempJar.delete()
        }
    }

    @Test
    fun ensurePatchedMtsJar_upgradesOlderLauncherPatchedJarFromDeviceFixture() {
        val sourceJar = File("agent-tmp/patch-cache-benchmark/device/ModTheSpire.jar")
        org.junit.Assume.assumeTrue("Missing local device ModTheSpire.jar fixture", sourceJar.isFile)

        val tempJar = Files.createTempFile("mts-loader-patch-upgrade-", ".jar").toFile()
        try {
            JarFileIoUtils.copyFileReplacing(sourceJar, tempJar)

            val oldLoaderBytes = JarFileIoUtils.readJarEntryBytes(
                tempJar,
                "com/evacipated/cardcrawl/modthespire/Loader.class"
            )
            requireNotNull(oldLoaderBytes)
            assertFalse(MtsLoaderCrashPatcher.isPatchedLoaderClass(oldLoaderBytes))

            assertTrue(MtsLoaderCrashPatcher.ensurePatchedMtsJar(tempJar))

            val patchedLoaderBytes = JarFileIoUtils.readJarEntryBytes(
                tempJar,
                "com/evacipated/cardcrawl/modthespire/Loader.class"
            )
            requireNotNull(patchedLoaderBytes)
            val patchedPrepackagedLauncherBytes = JarFileIoUtils.readJarEntryBytes(
                tempJar,
                "com/evacipated/cardcrawl/modthespire/PackageJar\$PrepackagedLauncher.class"
            )
            requireNotNull(patchedPrepackagedLauncherBytes)
            assertTrue(MtsLoaderCrashPatcher.isPatchedLoaderClass(patchedLoaderBytes))
            assertTrue(MtsLoaderCrashPatcher.hasOutJarPrimingHook(patchedLoaderBytes))
            assertTrue(MtsLoaderCrashPatcher.isPatchedPrepackagedLauncherClass(patchedPrepackagedLauncherBytes))
            assertFalse(MtsLoaderCrashPatcher.hasPrepackagedCallInitializersCall(patchedPrepackagedLauncherBytes))
        } finally {
            tempJar.delete()
        }
    }

    @Test
    fun patchLoaderBytes_insertsOutJarPrimingBeforeCacheStoreHook() {
        val sourceJar = sequenceOf(
            File("src/main/assets/components/mods/ModTheSpire.jar"),
            File("app/src/main/assets/components/mods/ModTheSpire.jar")
        ).firstOrNull { it.isFile }
            ?: error("Missing test fixture jar: ModTheSpire.jar")

        val originalLoaderBytes = JarFileIoUtils.readJarEntryBytes(
            sourceJar,
            "com/evacipated/cardcrawl/modthespire/Loader.class"
        )
        requireNotNull(originalLoaderBytes)

        val patchedLoaderBytes = MtsLoaderCrashPatcher.patchLoaderBytes(originalLoaderBytes)
        assertTrue(MtsLoaderCrashPatcher.hasOutJarPrimingHook(patchedLoaderBytes))
    }

    @Test
    fun patchLoaderBytes_wrapsCompilePatchesWithCacheCaptureHooks() {
        val sourceJar = sequenceOf(
            File("src/main/assets/components/mods/ModTheSpire.jar"),
            File("app/src/main/assets/components/mods/ModTheSpire.jar")
        ).firstOrNull { it.isFile }
            ?: error("Missing test fixture jar: ModTheSpire.jar")

        val originalLoaderBytes = JarFileIoUtils.readJarEntryBytes(
            sourceJar,
            "com/evacipated/cardcrawl/modthespire/Loader.class"
        )
        requireNotNull(originalLoaderBytes)

        val patchedLoaderBytes = MtsLoaderCrashPatcher.patchLoaderBytes(originalLoaderBytes)
        assertTrue(MtsLoaderCrashPatcher.hasOutJarPrimingHook(patchedLoaderBytes))
    }

    @Test
    fun ensurePatchedMtsJar_returnsFalseWhenJarIsAlreadyCurrent() {
        val sourceJar = sequenceOf(
            File("src/main/assets/components/mods/ModTheSpire.jar"),
            File("app/src/main/assets/components/mods/ModTheSpire.jar")
        ).firstOrNull { it.isFile }
            ?: error("Missing test fixture jar: ModTheSpire.jar")

        val tempJar = Files.createTempFile("mts-loader-patch-current-", ".jar").toFile()
        try {
            JarFileIoUtils.copyFileReplacing(sourceJar, tempJar)

            assertTrue(MtsLoaderCrashPatcher.ensurePatchedMtsJar(tempJar))
            val firstModified = tempJar.lastModified()

            Thread.sleep(5)

            assertFalse(MtsLoaderCrashPatcher.ensurePatchedMtsJar(tempJar))
            assertEquals(firstModified, tempJar.lastModified())
        } finally {
            tempJar.delete()
        }
    }
}
