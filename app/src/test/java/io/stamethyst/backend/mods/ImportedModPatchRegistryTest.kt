package io.stamethyst.backend.mods

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import io.stamethyst.backend.mods.importing.patches.ImportPatchRegistry
import io.stamethyst.backend.mods.importing.patches.ImportPatchSettings
import io.stamethyst.backend.mods.importing.patches.mods.chaofanmod.ChaofanModImportPatchModule
import io.stamethyst.backend.mods.importing.patches.mods.jacketnoanoko.JacketNoAnoKoImportPatchModule
import io.stamethyst.backend.mods.importing.patches.structure.DuplicateZipEntryPatchModule
import io.stamethyst.backend.mods.importing.patches.texture.AtlasFilterPatchModule
import io.stamethyst.backend.mods.importing.patches.texture.AtlasOfflineDownscalePatchModule
import io.stamethyst.config.RuntimePaths
import io.stamethyst.ui.preferences.LauncherPreferences
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.LinkedHashMap
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedModPatchRegistryTest {
    @Test
    fun readAll_infersV0RecordsForLegacyAggregateMetadata() {
        val roots = TestRoots.create("import-patch-legacy-metadata")
        val storagePath = File(RuntimePaths.optionalModsLibraryDir(roots.context), "Legacy.jar").absolutePath
        writeLegacyMetadata(
            context = roots.context,
            storagePath = storagePath,
            entry = JSONObject()
                .put("modId", "legacy")
                .put("modName", "Legacy Mod")
                .put("patchedFilterLines", 1)
                .put("downscaledAtlasPageEntries", 1)
                .put("patchedManifestRootEntries", 1)
                .put("patchedFrierenAntiPirateMethod", true)
                .put("patchedDownfallClassEntries", 1)
                .put("patchedVupShionWebButtonConstructor", true)
                .put("patchedChaofanModSteamworksHelperInitialization", true)
                .put("patchedJacketNoAnoKoShaderEntries", 1)
                .put("patchedOriShaderEntries", 1)
        )

        val patchInfo = ImportedModPatchRegistry.readAll(roots.context)[storagePath]

        assertNotNull(patchInfo)
        assertEquals(
            linkedMapOf(
                DuplicateZipEntryPatchModule.id to 0,
                "texture.atlas_filter" to 0,
                "texture.atlas_offline_downscale" to 0,
                "structure.manifest_root" to 0,
                "mod.frieren.anti_pirate" to 0,
                "mod.downfall.mobile_layout" to 0,
                "mod.vupshion.startup_compat" to 0,
                "mod.chaofanmod.steamworks" to 0,
                "mod.jacketnoanoko.shader" to 0,
                "mod.ori.fast_blur" to 0,
            ),
            patchInfo!!.appliedPatches.associate { it.moduleId to it.version }
        )
    }

    @Test
    fun readAll_infersDuplicateZipV0ForAnyLegacyCompatibilityMetadata() {
        val roots = TestRoots.create("import-patch-legacy-duplicate-zip")
        val storagePath = File(RuntimePaths.optionalModsLibraryDir(roots.context), "Legacy.jar").absolutePath
        writeLegacyMetadata(
            context = roots.context,
            storagePath = storagePath,
            entry = JSONObject()
                .put("modId", "legacy")
                .put("modName", "Legacy Mod")
                .put("patchedManifestRootEntries", 1)
        )

        val patchInfo = ImportedModPatchRegistry.readAll(roots.context)[storagePath]

        assertNotNull(patchInfo)
        assertEquals(0, patchInfo!!.appliedPatchVersion(DuplicateZipEntryPatchModule.id))
    }

    @Test
    fun putAndReadAll_roundTripsV1AppliedPatchRecords() {
        val roots = TestRoots.create("import-patch-v1-metadata")
        val storagePath = File(RuntimePaths.optionalModsLibraryDir(roots.context), "Current.jar").absolutePath
        val expectedRecords = listOf(
            ImportedModPatchRecord(AtlasFilterPatchModule.id, 1),
            ImportedModPatchRecord(ChaofanModImportPatchModule.id, 1),
        )

        ImportedModPatchRegistry.put(
            context = roots.context,
            storagePath = storagePath,
            patchInfo = ImportedModPatchInfo(
                modId = "current",
                modName = "Current Mod",
                appliedPatches = expectedRecords,
            )
        )

        val metadataFile = RuntimePaths.importedModPatchMetadataFile(roots.context)
        assertTrue(metadataFile.isFile)
        val root = JSONObject(metadataFile.readText(StandardCharsets.UTF_8))
        assertEquals(2, root.getInt("version"))
        val savedRecords = root.getJSONObject("entries")
            .getJSONObject(storagePath)
            .getJSONArray("appliedPatches")
        assertEquals(2, savedRecords.length())
        assertEquals(AtlasFilterPatchModule.id, savedRecords.getJSONObject(0).getString("moduleId"))
        assertEquals(1, savedRecords.getJSONObject(0).getInt("version"))

        val restored = ImportedModPatchRegistry.readAll(roots.context)[storagePath]
        assertNotNull(restored)
        assertEquals(expectedRecords, restored!!.appliedPatches)
        assertEquals(1, restored.appliedPatchVersion(AtlasFilterPatchModule.id))
    }

    @Test
    fun registry_marksOnlyOlderKnownAppliedRecordsAsOutdated() {
        val roots = TestRoots.create("import-patch-version-state")
        val legacyInfo = ImportedModPatchInfo(
            modId = "legacy",
            modName = "Legacy Mod",
            appliedPatches = listOf(
                ImportedModPatchRecord(AtlasFilterPatchModule.id, 0),
                ImportedModPatchRecord("removed.patch", 0),
            )
        )
        val currentInfo = legacyInfo.copy(
            appliedPatches = listOf(
                ImportedModPatchRecord(AtlasFilterPatchModule.id, 1),
                ImportedModPatchRecord("removed.patch", 0),
            )
        )

        assertTrue(ImportPatchRegistry.hasOutdatedAppliedPatches(legacyInfo))
        assertFalse(ImportPatchRegistry.hasOutdatedAppliedPatches(currentInfo))
        assertEquals(1, ImportPatchRegistry.currentVersion(AtlasFilterPatchModule.id))
        assertNull(ImportPatchRegistry.currentVersion("removed.patch"))
    }

    @Test
    fun importPatchEnablement_persistsForEveryRegisteredModule() {
        val roots = TestRoots.create("import-patch-enablement")
        val modules = ImportPatchRegistry.modules(roots.context)

        assertTrue(modules.isNotEmpty())
        modules.forEach { module ->
            assertTrue(ImportPatchRegistry.setEnabled(roots.context, module.id, !module.defaultEnabled))
        }

        modules.forEach { module ->
            assertEquals(
                !module.defaultEnabled,
                ImportPatchRegistry.isEnabled(roots.context, module.id)
            )
        }
        assertFalse(ImportPatchRegistry.setEnabled(roots.context, "missing.patch", true))
        assertFalse(ImportPatchRegistry.isEnabled(roots.context, "missing.patch"))
    }

    @Test
    fun atlasDownscale_usesLegacyWorkshopValueUntilTheManagerHasAnOverride() {
        val roots = TestRoots.create("atlas-downscale-setting-migration")

        assertFalse(ImportPatchRegistry.isEnabled(roots.context, AtlasOfflineDownscalePatchModule.id))

        LauncherPreferences.setWorkshopAutoImportAtlasDownscaleEnabled(roots.context, true)
        assertTrue(ImportPatchRegistry.isEnabled(roots.context, AtlasOfflineDownscalePatchModule.id))

        assertTrue(
            ImportPatchRegistry.setEnabled(
                roots.context,
                AtlasOfflineDownscalePatchModule.id,
                false
            )
        )
        assertFalse(LauncherPreferences.isWorkshopAutoImportAtlasDownscaleEnabled(roots.context))
        assertFalse(ImportPatchRegistry.isEnabled(roots.context, AtlasOfflineDownscalePatchModule.id))

        assertTrue(
            ImportPatchRegistry.setEnabled(
                roots.context,
                AtlasOfflineDownscalePatchModule.id,
                true
            )
        )
        assertTrue(LauncherPreferences.isWorkshopAutoImportAtlasDownscaleEnabled(roots.context))

        LauncherPreferences.setWorkshopAutoImportAtlasDownscaleEnabled(roots.context, false)
        assertTrue(ImportPatchRegistry.isEnabled(roots.context, AtlasOfflineDownscalePatchModule.id))
    }

    @Test
    fun resetImportPatchSettings_restoresManagerAndLegacyDefaultsTogether() {
        val roots = TestRoots.create("import-patch-settings-reset")

        assertTrue(
            ImportPatchRegistry.setEnabled(
                roots.context,
                AtlasOfflineDownscalePatchModule.id,
                true
            )
        )
        assertTrue(LauncherPreferences.isWorkshopAutoImportAtlasDownscaleEnabled(roots.context))
        assertTrue(
            ImportPatchRegistry.setEnabled(
                roots.context,
                JacketNoAnoKoImportPatchModule.id,
                false
            )
        )
        assertFalse(CompatibilitySettings.isJacketNoAnoKoModCompatEnabled(roots.context))

        ImportPatchSettings.resetToDefaults(roots.context)

        assertFalse(ImportPatchRegistry.isEnabled(roots.context, AtlasOfflineDownscalePatchModule.id))
        assertFalse(LauncherPreferences.isWorkshopAutoImportAtlasDownscaleEnabled(roots.context))
        assertTrue(ImportPatchRegistry.isEnabled(roots.context, JacketNoAnoKoImportPatchModule.id))
        assertTrue(CompatibilitySettings.isJacketNoAnoKoModCompatEnabled(roots.context))
    }

    private fun writeLegacyMetadata(context: Context, storagePath: String, entry: JSONObject) {
        val metadataFile = RuntimePaths.importedModPatchMetadataFile(context)
        assertTrue(metadataFile.parentFile!!.mkdirs() || metadataFile.parentFile!!.isDirectory)
        metadataFile.writeText(
            JSONObject()
                .put("version", 1)
                .put("entries", JSONObject().put(storagePath, entry))
                .toString(),
            StandardCharsets.UTF_8
        )
    }

    private class TestRoots private constructor(
        val context: Context,
    ) {
        companion object {
            fun create(prefix: String): TestRoots {
                val rootDir = Files.createTempDirectory(prefix).toFile()
                val filesDir = File(rootDir, "internal-files").apply { mkdirs() }
                val externalFilesDir = File(rootDir, "external-files").apply { mkdirs() }
                val preferences = LinkedHashMap<String, InMemorySharedPreferences>()
                return TestRoots(
                    context = object : ContextWrapper(Application()) {
                        override fun getFilesDir(): File = filesDir

                        override fun getExternalFilesDir(type: String?): File = externalFilesDir

                        override fun getApplicationContext(): Context = this

                        override fun getPackageName(): String = "io.stamethyst.test"

                        override fun getSharedPreferences(
                            name: String,
                            mode: Int
                        ): SharedPreferences = preferences.getOrPut(name) {
                            InMemorySharedPreferences()
                        }
                    }
                )
            }
        }
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val values = LinkedHashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = synchronized(values) { LinkedHashMap(values) }

        override fun getString(key: String, defValue: String?): String? =
            synchronized(values) { values[key] as? String ?: defValue }

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?
        ): MutableSet<String>? = synchronized(values) {
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues
        }

        override fun getInt(key: String, defValue: Int): Int =
            synchronized(values) { values[key] as? Int ?: defValue }

        override fun getLong(key: String, defValue: Long): Long =
            synchronized(values) { values[key] as? Long ?: defValue }

        override fun getFloat(key: String, defValue: Float): Float =
            synchronized(values) { values[key] as? Float ?: defValue }

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            synchronized(values) { values[key] as? Boolean ?: defValue }

        override fun contains(key: String): Boolean = synchronized(values) { values.containsKey(key) }

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private val removals = LinkedHashSet<String>()
            private var clear = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
                pending[key] = value
            }

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?
            ): SharedPreferences.Editor = apply {
                pending[key] = values?.toMutableSet()
            }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
                pending[key] = value
            }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
                pending[key] = value
            }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
                pending[key] = value
            }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
                pending[key] = value
            }

            override fun remove(key: String): SharedPreferences.Editor = apply {
                removals += key
            }

            override fun clear(): SharedPreferences.Editor = apply {
                clear = true
            }

            override fun commit(): Boolean {
                synchronized(values) {
                    if (clear) values.clear()
                    removals.forEach(values::remove)
                    pending.forEach { (key, value) ->
                        if (value == null) values.remove(key) else values[key] = value
                    }
                }
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
