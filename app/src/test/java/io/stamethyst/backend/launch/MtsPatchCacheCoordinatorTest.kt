package io.stamethyst.backend.launch

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import io.stamethyst.config.RuntimePaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class MtsPatchCacheCoordinatorTest {
    @Test
    fun cacheMarkerChangesWhenModFileChanges() {
        val root = Files.createTempDirectory("mts-patch-cache-key-").toFile()
        try {
            val desktopJar = writeFile(root, "desktop-1.0.jar", "desktop")
            val mtsJar = writeFile(root, "ModTheSpire.jar", "mts")
            val baseModJar = writeFile(root, "BaseMod.jar", "basemod")
            val stsLibJar = writeFile(root, "StSLib.jar", "stslib")
            val bootBridgeJar = writeFile(root, "boot-bridge.jar", "bootbridge")
            val gdxPatchJar = writeFile(root, "gdx-patch.jar", "gdx")
            val modJar = writeFile(root, "ExampleMod.jar", "mod-v1")
            val modFileList = writeFile(root, ".mts_mod_file_list", modJar.absolutePath + "\n")

            val first = MtsPatchCacheCoordinator.buildCacheMarkerValue(
                desktopJar = desktopJar,
                mtsJar = mtsJar,
                baseModJar = baseModJar,
                stsLibJar = stsLibJar,
                bootBridgeJar = bootBridgeJar,
                gdxPatchJar = gdxPatchJar,
                modFileList = modFileList
            )

            Thread.sleep(5)
            modJar.writeText("mod-v2", StandardCharsets.UTF_8)

            val second = MtsPatchCacheCoordinator.buildCacheMarkerValue(
                desktopJar = desktopJar,
                mtsJar = mtsJar,
                baseModJar = baseModJar,
                stsLibJar = stsLibJar,
                bootBridgeJar = bootBridgeJar,
                gdxPatchJar = gdxPatchJar,
                modFileList = modFileList
            )

            assertNotEquals(first, second)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cacheMarkerDoesNotChangeWhenModFileListMtimeChangesWithoutContentChange() {
        val root = Files.createTempDirectory("mts-patch-cache-list-mtime-").toFile()
        try {
            val desktopJar = writeFile(root, "desktop-1.0.jar", "desktop")
            val mtsJar = writeFile(root, "ModTheSpire.jar", "mts")
            val baseModJar = writeFile(root, "BaseMod.jar", "basemod")
            val stsLibJar = writeFile(root, "StSLib.jar", "stslib")
            val bootBridgeJar = writeFile(root, "boot-bridge.jar", "bootbridge")
            val gdxPatchJar = writeFile(root, "gdx-patch.jar", "gdx")
            val modJar = writeFile(root, "ExampleMod.jar", "mod")
            val modFileList = writeFile(root, ".mts_mod_file_list", modJar.absolutePath + "\n")

            val first = MtsPatchCacheCoordinator.buildCacheMarkerValue(
                desktopJar = desktopJar,
                mtsJar = mtsJar,
                baseModJar = baseModJar,
                stsLibJar = stsLibJar,
                bootBridgeJar = bootBridgeJar,
                gdxPatchJar = gdxPatchJar,
                modFileList = modFileList
            )

            Thread.sleep(5)
            modFileList.setLastModified(System.currentTimeMillis())

            val second = MtsPatchCacheCoordinator.buildCacheMarkerValue(
                desktopJar = desktopJar,
                mtsJar = mtsJar,
                baseModJar = baseModJar,
                stsLibJar = stsLibJar,
                bootBridgeJar = bootBridgeJar,
                gdxPatchJar = gdxPatchJar,
                modFileList = modFileList
            )

            assertEquals(first, second)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cacheMarkerChangesWhenBootBridgeChanges() {
        val root = Files.createTempDirectory("mts-patch-cache-boot-bridge-").toFile()
        try {
            val desktopJar = writeFile(root, "desktop-1.0.jar", "desktop")
            val mtsJar = writeFile(root, "ModTheSpire.jar", "mts")
            val baseModJar = writeFile(root, "BaseMod.jar", "basemod")
            val stsLibJar = writeFile(root, "StSLib.jar", "stslib")
            val bootBridgeJar = writeFile(root, "boot-bridge.jar", "bootbridge-v1")
            val gdxPatchJar = writeFile(root, "gdx-patch.jar", "gdx")
            val modJar = writeFile(root, "ExampleMod.jar", "mod")
            val modFileList = writeFile(root, ".mts_mod_file_list", modJar.absolutePath + "\n")

            val first = MtsPatchCacheCoordinator.buildCacheMarkerValue(
                desktopJar = desktopJar,
                mtsJar = mtsJar,
                baseModJar = baseModJar,
                stsLibJar = stsLibJar,
                bootBridgeJar = bootBridgeJar,
                gdxPatchJar = gdxPatchJar,
                modFileList = modFileList
            )

            Thread.sleep(5)
            bootBridgeJar.writeText("bootbridge-v2", StandardCharsets.UTF_8)

            val second = MtsPatchCacheCoordinator.buildCacheMarkerValue(
                desktopJar = desktopJar,
                mtsJar = mtsJar,
                baseModJar = baseModJar,
                stsLibJar = stsLibJar,
                bootBridgeJar = bootBridgeJar,
                gdxPatchJar = gdxPatchJar,
                modFileList = modFileList
            )

            assertNotEquals(first, second)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cacheCurrentRequiresMarkerAndPackagedJar() {
        val root = Files.createTempDirectory("mts-patch-cache-current-").toFile()
        try {
            val desktopJar = writeFile(root, "desktop-1.0.jar", "desktop")
            val mtsJar = writeFile(root, "ModTheSpire.jar", "mts")
            val baseModJar = writeFile(root, "BaseMod.jar", "basemod")
            val stsLibJar = writeFile(root, "StSLib.jar", "stslib")
            val bootBridgeJar = writeFile(root, "boot-bridge.jar", "bootbridge")
            val gdxPatchJar = writeFile(root, "gdx-patch.jar", "gdx")
            val modJar = writeFile(root, "ExampleMod.jar", "mod")
            val modFileList = writeFile(root, ".mts_mod_file_list", modJar.absolutePath + "\n")
            val cachedJar = File(root, "desktop-1.0-modded.jar")
            val markerFile = File(root, ".mts_patch_cache")
            val packageDir = File(root, "package")

            val marker = MtsPatchCacheCoordinator.buildCacheMarkerValue(
                desktopJar = desktopJar,
                mtsJar = mtsJar,
                baseModJar = baseModJar,
                stsLibJar = stsLibJar,
                bootBridgeJar = bootBridgeJar,
                gdxPatchJar = gdxPatchJar,
                modFileList = modFileList
            )
            markerFile.writeText(marker, StandardCharsets.UTF_8)

            assertFalse(
                MtsPatchCacheCoordinator.isCacheCurrent(
                    markerFile = markerFile,
                    cachedJar = cachedJar,
                    packageDir = packageDir,
                    expectedMarker = marker
                )
            )

            cachedJar.writeBytes(ByteArray(1024 * 1024) { 1 })
            writeFile(packageDir, "ExampleMod-modded.jar", "modded")

            assertTrue(
                MtsPatchCacheCoordinator.isCacheCurrent(
                    markerFile = markerFile,
                    cachedJar = cachedJar,
                    packageDir = packageDir,
                    expectedMarker = marker
                )
            )

            File(packageDir, "ExampleMod-modded.jar").delete()

            assertFalse(
                MtsPatchCacheCoordinator.isCacheCurrent(
                    markerFile = markerFile,
                    cachedJar = cachedJar,
                    packageDir = packageDir,
                    expectedMarker = marker
                )
            )

            writeFile(packageDir, "ExampleMod-modded.jar", "modded")

            assertFalse(
                MtsPatchCacheCoordinator.isCacheCurrent(
                    markerFile = markerFile,
                    cachedJar = cachedJar,
                    packageDir = packageDir,
                    expectedMarker = "$marker\nchanged"
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cacheRuntimePropertiesAreAppendedWhenCurrent() {
        val args = arrayListOf<String>()
        val cachedJar = File("desktop-1.0-modded.jar")
        val baseJar = File("desktop-1.0.jar")
        val markerFile = File(".mts_patch_cache")
        val packageDir = File("package")
        val gameDir = File("sts")
        val loadoutScanCacheDir = File("loadout-scan-cache")

        MtsPatchCacheCoordinator.appendRuntimeProperties(
            args = args,
            enabled = true,
            cacheCurrent = true,
            cachedJar = cachedJar,
            baseJar = baseJar,
            markerFile = markerFile,
            packageDir = packageDir,
            expectedMarker = "marker",
            gameDir = gameDir,
            loadoutScanCacheDir = loadoutScanCacheDir
        )

        assertEquals(
            listOf(
                "-Damethyst.mts.patch_cache.enabled=true",
                "-Damethyst.mts.patch_cache.current=true",
                "-Damethyst.mts.patch_cache.jar=${cachedJar.absolutePath}",
                "-Damethyst.mts.patch_cache.base_jar=${baseJar.absolutePath}",
                "-Damethyst.mts.patch_cache.marker=${markerFile.absolutePath}",
                "-Damethyst.mts.patch_cache.package_dir=${packageDir.absolutePath}",
                "-Damethyst.mts.patch_cache.expected=marker",
                "-Damethyst.mts.patch_cache.game_dir=${gameDir.absolutePath}",
                "-Damethyst.runtime_compat.loadout_class_scan_cache=true",
                "-Damethyst.loadout.scan_cache_dir=${loadoutScanCacheDir.absolutePath}"
            ),
            args
        )
    }

    @Test
    fun cacheRuntimePropertiesStayDisabledWhenFeatureIsOff() {
        val args = arrayListOf<String>()
        val cachedJar = File("desktop-1.0-modded.jar")
        val baseJar = File("desktop-1.0.jar")
        val markerFile = File(".mts_patch_cache")
        val packageDir = File("package")
        val gameDir = File("sts")
        val loadoutScanCacheDir = File("loadout-scan-cache")

        MtsPatchCacheCoordinator.appendRuntimeProperties(
            args = args,
            enabled = false,
            cacheCurrent = false,
            cachedJar = cachedJar,
            baseJar = baseJar,
            markerFile = markerFile,
            packageDir = packageDir,
            expectedMarker = "",
            gameDir = gameDir,
            loadoutScanCacheDir = loadoutScanCacheDir
        )

        assertEquals(
            listOf(
                "-Damethyst.mts.patch_cache.enabled=false",
                "-Damethyst.mts.patch_cache.current=false",
                "-Damethyst.mts.patch_cache.jar=${cachedJar.absolutePath}",
                "-Damethyst.mts.patch_cache.base_jar=${baseJar.absolutePath}",
                "-Damethyst.mts.patch_cache.marker=${markerFile.absolutePath}",
                "-Damethyst.mts.patch_cache.package_dir=${packageDir.absolutePath}",
                "-Damethyst.mts.patch_cache.expected=",
                "-Damethyst.mts.patch_cache.game_dir=${gameDir.absolutePath}",
                "-Damethyst.runtime_compat.loadout_class_scan_cache=false",
                "-Damethyst.loadout.scan_cache_dir=${loadoutScanCacheDir.absolutePath}"
            ),
            args
        )
    }

    @Test
    fun clearRemovesCurrentAndLegacyCacheArtifacts() {
        val roots = TestRoots.create("mts-patch-cache-clear-")
        try {
            writeFile(RuntimePaths.mtsPatchCacheJar(roots.context), "current-cache")
            writeFile(File(RuntimePaths.mtsPatchCachePackageDir(roots.context), "ExampleMod.jar"), "pkg")
            writeFile(File(RuntimePaths.legacyInternalStsRoot(roots.context), "desktop-1.0-modded.jar"), "legacy-internal")
            writeFile(
                File(RuntimePaths.legacyInternalStsRoot(roots.context), "mts_patch_cache/loadout-scan-cache/cache.bin"),
                "legacy-internal-scan"
            )
            writeFile(
                File(requireNotNull(RuntimePaths.externalAppStsRoot(roots.context)), ".mts_patch_cache"),
                "legacy-external"
            )

            MtsPatchCacheCoordinator.clear(roots.context)

            assertFalse(RuntimePaths.mtsPatchCacheDir(roots.context).exists())
            assertFalse(File(RuntimePaths.legacyInternalStsRoot(roots.context), "desktop-1.0-modded.jar").exists())
            assertFalse(File(RuntimePaths.legacyInternalStsRoot(roots.context), "mts_patch_cache").exists())
            assertFalse(
                File(requireNotNull(RuntimePaths.externalAppStsRoot(roots.context)), ".mts_patch_cache").exists()
            )
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }

    private fun writeFile(root: File, name: String, text: String): File {
        val file = File(root, name)
        file.parentFile?.mkdirs()
        file.writeText(text, StandardCharsets.UTF_8)
        return file
    }

    private fun writeFile(file: File, text: String): File {
        file.parentFile?.mkdirs()
        file.writeText(text, StandardCharsets.UTF_8)
        return file
    }

    private class TestRoots private constructor(
        val rootDir: File,
        val context: Context
    ) {
        companion object {
            fun create(prefix: String): TestRoots {
                val rootDir = Files.createTempDirectory(prefix).toFile()
                val filesDir = File(rootDir, "internal-files").apply { mkdirs() }
                val externalFilesDir = File(rootDir, "external-files").apply { mkdirs() }
                return TestRoots(
                    rootDir = rootDir,
                    context = object : ContextWrapper(Application()) {
                        override fun getFilesDir(): File = filesDir

                        override fun getExternalFilesDir(type: String?): File = externalFilesDir

                        override fun getPackageName(): String = "io.stamethyst.test"
                    }
                )
            }
        }
    }
}
