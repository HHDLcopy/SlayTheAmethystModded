package io.stamethyst.backend.mods.importing

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Resources
import io.stamethyst.backend.mods.CompatibilitySettings
import io.stamethyst.backend.mods.ImportedModPatchRegistry
import io.stamethyst.backend.mods.ReservedCoreModComponents
import io.stamethyst.backend.mods.importing.patches.ImportPatchRegistry
import io.stamethyst.backend.mods.importing.patches.mods.chaofanmod.ChaofanModImportPatchModule
import io.stamethyst.backend.mods.importing.patches.structure.DuplicateZipEntryPatchModule
import io.stamethyst.backend.mods.importing.patches.structure.ManifestRootPatchModule
import io.stamethyst.backend.mods.importing.patches.texture.AtlasOfflineDownscalePatchModule
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class ModImportPlannerReservedCoreTest {
    @Test
    fun planLocalFiles_addsChaofanModCompatPatchWhenEnabled() {
        val roots = TestRoots.create("mod-import-planner-chaofanmod")
        val jarFile = File(roots.rootDir, "chaofanmod.jar")
        writeModJar(
            jarFile = jarFile,
            modId = "chaofanmod",
            name = "Chaofan Mod",
        )

        val plan = ModImportPlanner.planLocalFiles(roots.context, listOf(jarFile))
        try {
            val item = plan.importableItems.single()
            assertTrue(
                item.patchPlans.any { patch -> patch.moduleId == ChaofanModImportPatchModule.id }
            )
        } finally {
            ModImportPlanner.cleanup(plan.session)
        }
    }

    @Test
    fun planLocalFiles_skipsChaofanModCompatPatchWhenDisabled() {
        val roots = TestRoots.create("mod-import-planner-chaofanmod-disabled")
        CompatibilitySettings.setChaofanModCompatEnabled(roots.context, false)
        val jarFile = File(roots.rootDir, "chaofanmod.jar")
        writeModJar(
            jarFile = jarFile,
            modId = "chaofanmod",
            name = "Chaofan Mod",
        )

        val plan = ModImportPlanner.planLocalFiles(roots.context, listOf(jarFile))
        try {
            val item = plan.importableItems.single()
            assertFalse(
                item.patchPlans.any { patch -> patch.moduleId == ChaofanModImportPatchModule.id }
            )
        } finally {
            ModImportPlanner.cleanup(plan.session)
        }
    }

    @Test
    fun planLocalFiles_skipsChaofanModCompatPatchWhenImportPatchManagerDisablesIt() {
        val roots = TestRoots.create("mod-import-planner-chaofanmod-manager-disabled")
        assertTrue(
            ImportPatchRegistry.setEnabled(
                roots.context,
                ChaofanModImportPatchModule.id,
                false
            )
        )
        val jarFile = File(roots.rootDir, "chaofanmod.jar")
        writeModJar(
            jarFile = jarFile,
            modId = "chaofanmod",
            name = "Chaofan Mod",
        )

        val plan = ModImportPlanner.planLocalFiles(roots.context, listOf(jarFile))
        try {
            val item = plan.importableItems.single()
            assertFalse(
                item.patchPlans.any { patch -> patch.moduleId == ChaofanModImportPatchModule.id }
            )
        } finally {
            ModImportPlanner.cleanup(plan.session)
        }
    }

    @Test
    fun deferredImportPlan_usesManagerEnablementAsThePatchDefault() {
        val roots = TestRoots.create("mod-import-planner-manager-default")
        assertTrue(
            ImportPatchRegistry.setEnabled(
                roots.context,
                AtlasOfflineDownscalePatchModule.id,
                true
            )
        )
        val jarFile = File(roots.rootDir, "regularmod.jar")
        writeModJar(
            jarFile = jarFile,
            modId = "regularmod",
            name = "Regular Mod",
        )

        val plan = ModImportPlanner.planLocalFiles(
            context = roots.context,
            files = listOf(jarFile),
            options = ModImportPlanningOptions(
                includeUserConfigurablePatches = true,
                deferUserConfigurablePatchInspection = true,
            )
        )
        try {
            val downscalePlan = plan.importableItems.single().patchPlans.first {
                it.moduleId == AtlasOfflineDownscalePatchModule.id
            }
            assertTrue(downscalePlan.defaultEnabled)
        } finally {
            ModImportPlanner.cleanup(plan.session)
        }
    }

    @Test
    fun execute_recordsChaofanModPatchMetadataForMainListBadge() {
        val roots = TestRoots.create("mod-import-executor-chaofanmod")
        val jarFile = File(roots.rootDir, "chaofanmod.jar")
        writeChaofanModJar(jarFile)
        val plan = ModImportPlanner.planLocalFiles(roots.context, listOf(jarFile))

        try {
            val report = ModImportExecutor.execute(
                context = roots.context,
                plan = plan,
                decisions = ModImportDecisions()
            )

            val imported = report.importedResults.single()
            val patchInfo = ImportedModPatchRegistry.readAll(roots.context)[imported.storagePath]
            assertNotNull(patchInfo)
            assertTrue(patchInfo!!.wasChaofanModPatched)
            assertTrue(patchInfo.hasCompatibilityPatches)
            assertEquals(1, patchInfo.appliedPatchVersion(ChaofanModImportPatchModule.id))
        } finally {
            ModImportPlanner.cleanup(plan.session)
        }
    }

    @Test
    fun execute_doesNotReuseInspectionJarWhenPerItemStructuralPatchIsDisabled() {
        val roots = TestRoots.create("mod-import-executor-duplicate-zip-disabled")
        val jarFile = File(roots.rootDir, "duplicate-entry.jar")
        writeJarWithDuplicateMetadataEntry(jarFile)
        val sourceBytes = jarFile.readBytes()
        val plan = ModImportPlanner.planLocalFiles(roots.context, listOf(jarFile))

        try {
            val item = plan.importableItems.single()
            val duplicatePlan = item.patchPlans.single {
                it.moduleId == DuplicateZipEntryPatchModule.id
            }
            val patchEvents = ArrayList<ModImportPatchExecutionEvent>()

            val report = ModImportExecutor.execute(
                context = roots.context,
                plan = plan,
                decisions = ModImportDecisions(
                    patchEnabledByKey = mapOf(
                        ModImportDecisions.patchDecisionKey(item.id, duplicatePlan.moduleId) to false
                    )
                ),
                onPatchEvent = patchEvents::add
            )

            val imported = report.importedResults.single()
            val outputBytes = File(checkNotNull(imported.storagePath)).readBytes()
            assertArrayEquals(sourceBytes, jarFile.readBytes())
            assertArrayEquals(sourceBytes, outputBytes)
            assertFalse(
                report.appliedPatchResults.any { it.moduleId == DuplicateZipEntryPatchModule.id }
            )
            assertTrue(
                patchEvents.any { event ->
                    event is ModImportPatchExecutionEvent.Skipped &&
                        event.patchPlan.moduleId == DuplicateZipEntryPatchModule.id &&
                        event.reason == ModImportPatchSkipReason.DisabledByDecision
                }
            )
        } finally {
            ModImportPlanner.cleanup(plan.session)
        }
    }

    @Test
    fun execute_doesNotReuseInspectionJarWhenStructuralPatchIsGloballyDisabled() {
        val roots = TestRoots.create("mod-import-executor-duplicate-zip-global-disabled")
        assertTrue(
            ImportPatchRegistry.setEnabled(
                roots.context,
                DuplicateZipEntryPatchModule.id,
                false
            )
        )
        val jarFile = File(roots.rootDir, "duplicate-entry.jar")
        writeJarWithDuplicateMetadataEntry(jarFile)
        val sourceBytes = jarFile.readBytes()
        val plan = ModImportPlanner.planLocalFiles(roots.context, listOf(jarFile))

        try {
            val item = plan.importableItems.single()
            assertFalse(
                item.patchPlans.any { it.moduleId == DuplicateZipEntryPatchModule.id }
            )

            val report = ModImportExecutor.execute(
                context = roots.context,
                plan = plan,
                decisions = ModImportDecisions()
            )

            val imported = report.importedResults.single()
            val outputBytes = File(checkNotNull(imported.storagePath)).readBytes()
            assertArrayEquals(sourceBytes, jarFile.readBytes())
            assertArrayEquals(sourceBytes, outputBytes)
            assertFalse(
                report.appliedPatchResults.any { it.moduleId == DuplicateZipEntryPatchModule.id }
            )
        } finally {
            ModImportPlanner.cleanup(plan.session)
        }
    }

    @Test
    fun planLocalFiles_doesNotReadManifestFromDisabledManifestRootInspectionPatch() {
        val roots = TestRoots.create("mod-import-planner-manifest-root-disabled")
        assertTrue(
            ImportPatchRegistry.setEnabled(
                roots.context,
                ManifestRootPatchModule.id,
                false
            )
        )
        val jarFile = File(roots.rootDir, "nested-manifest.jar")
        writeNestedManifestJar(jarFile)

        val plan = ModImportPlanner.planLocalFiles(roots.context, listOf(jarFile))
        try {
            val item = plan.items.single()
            assertEquals(ModImportItemStatus.BLOCKED, item.status)
            assertEquals(ModImportBlockingReason.InvalidMtsLaunchManifest, item.blockingReason)
            assertTrue(item.patchPlans.isEmpty())
        } finally {
            ModImportPlanner.cleanup(plan.session)
        }
    }

    @Test
    fun planLocalFiles_blocksAmethystRuntimeCompatAsReservedCoreComponent() {
        val roots = TestRoots.create("mod-import-planner-runtime-compat")
        val jarFile = File(roots.rootDir, "AmethystRuntimeCompat.jar")
        writeModJar(
            jarFile = jarFile,
            modId = "amethystruntimecompat",
            name = "Amethyst Runtime Compat",
        )

        val plan = ModImportPlanner.planLocalFiles(roots.context, listOf(jarFile))
        try {
            val item = plan.blockedItems.single()
            assertEquals(ModImportBlockingReason.ReservedCoreComponent, item.blockingReason)
            assertEquals(ReservedCoreModComponents.AMETHYST_RUNTIME_COMPAT, item.reservedComponent)
        } finally {
            ModImportPlanner.cleanup(plan.session)
        }
    }

    @Test
    fun planLocalFiles_blocksRamSaverAsReservedCoreComponent() {
        val roots = TestRoots.create("mod-import-planner-ram-saver")
        val jarFile = File(roots.rootDir, "RamSaver.jar")
        writeModJar(
            jarFile = jarFile,
            modId = "ramsaver",
            name = "Ram Saver",
        )

        val plan = ModImportPlanner.planLocalFiles(roots.context, listOf(jarFile))
        try {
            val item = plan.blockedItems.single()
            assertEquals(ModImportBlockingReason.ReservedCoreComponent, item.blockingReason)
            assertEquals(ReservedCoreModComponents.RAM_SAVER, item.reservedComponent)
        } finally {
            ModImportPlanner.cleanup(plan.session)
        }
    }

    private fun writeModJar(jarFile: File, modId: String, name: String) {
        ZipOutputStream(jarFile.outputStream()).use { zipOut ->
            zipOut.putNextEntry(ZipEntry("ModTheSpire.json"))
            zipOut.write(
                """
                    {
                      "modid": "$modId",
                      "name": "$name"
                    }
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            )
            zipOut.closeEntry()
        }
        assertTrue(jarFile.isFile)
    }

    private fun writeChaofanModJar(jarFile: File) {
        ZipOutputStream(jarFile.outputStream()).use { zipOut ->
            zipOut.putNextEntry(ZipEntry("ModTheSpire.json"))
            zipOut.write(
                """
                    {
                      "modid": "chaofanmod",
                      "name": "Chaofan Mod"
                    }
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            )
            zipOut.closeEntry()

            zipOut.putNextEntry(ZipEntry("io/chaofan/sts/chaofanmod/ChaofanMod.class"))
            zipOut.write(buildChaofanModClassBytes())
            zipOut.closeEntry()
        }
        assertTrue(jarFile.isFile)
    }

    private fun writeNestedManifestJar(jarFile: File) {
        ZipOutputStream(jarFile.outputStream()).use { zipOut ->
            zipOut.putNextEntry(ZipEntry("nested/ModTheSpire.json"))
            zipOut.write(
                """
                    {
                      "modid": "nestedmanifest",
                      "name": "Nested Manifest"
                    }
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            )
            zipOut.closeEntry()
            zipOut.putNextEntry(ZipEntry("nested/com/example/Marker.class"))
            zipOut.write(byteArrayOf(0, 1, 2))
            zipOut.closeEntry()
        }
        assertTrue(jarFile.isFile)
    }

    private fun writeJarWithDuplicateMetadataEntry(jarFile: File) {
        ZipArchiveOutputStream(jarFile).use { zipOut ->
            writeArchiveEntry(
                zipOut = zipOut,
                entryName = "ModTheSpire.json",
                bytes = """
                    {
                      "modid": "duplicateentry",
                      "name": "Duplicate Entry"
                    }
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            )
            writeArchiveEntry(
                zipOut = zipOut,
                entryName = "META-INF/example.txt",
                bytes = "first".toByteArray(StandardCharsets.UTF_8)
            )
            writeArchiveEntry(
                zipOut = zipOut,
                entryName = "META-INF/example.txt",
                bytes = "second".toByteArray(StandardCharsets.UTF_8)
            )
        }
        assertTrue(jarFile.isFile)
    }

    private fun writeArchiveEntry(
        zipOut: ZipArchiveOutputStream,
        entryName: String,
        bytes: ByteArray
    ) {
        zipOut.putArchiveEntry(ZipArchiveEntry(entryName))
        zipOut.write(bytes)
        zipOut.closeArchiveEntry()
    }

    private fun buildChaofanModClassBytes(): ByteArray {
        val classWriter = ClassWriter(ClassWriter.COMPUTE_MAXS)
        classWriter.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC,
            "io/chaofan/sts/chaofanmod/ChaofanMod",
            null,
            "java/lang/Object",
            null
        )
        classWriter.visitField(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "steamworksHelper",
            "Lio/chaofan/sts/chaofanmod/utils/SteamworksHelper;",
            null,
            null
        ).visitEnd()
        classWriter.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        classWriter.visitMethod(Opcodes.ACC_PUBLIC, "receivePostInitialize", "()V", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, "io/chaofan/sts/chaofanmod/utils/SteamworksHelper")
            visitInsn(Opcodes.DUP)
            visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "io/chaofan/sts/chaofanmod/utils/SteamworksHelper",
                "<init>",
                "()V",
                false
            )
            visitFieldInsn(
                Opcodes.PUTSTATIC,
                "io/chaofan/sts/chaofanmod/ChaofanMod",
                "steamworksHelper",
                "Lio/chaofan/sts/chaofanmod/utils/SteamworksHelper;"
            )
            visitFieldInsn(
                Opcodes.GETSTATIC,
                "io/chaofan/sts/chaofanmod/ChaofanMod",
                "steamworksHelper",
                "Lio/chaofan/sts/chaofanmod/utils/SteamworksHelper;"
            )
            visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "basemod/BaseMod",
                "subscribe",
                "(Lbasemod/interfaces/ISubscriber;)V",
                false
            )
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        classWriter.visitEnd()
        return classWriter.toByteArray()
    }

    private class TestRoots private constructor(
        val rootDir: File,
        val context: Context,
    ) {
        companion object {
            fun create(prefix: String): TestRoots {
                val rootDir = Files.createTempDirectory(prefix).toFile()
                val filesDir = File(rootDir, "internal-files").apply { mkdirs() }
                val cacheDir = File(rootDir, "cache").apply { mkdirs() }
                val externalFilesDir = File(rootDir, "external-files").apply { mkdirs() }
                val prefs = LinkedHashMap<String, InMemorySharedPreferences>()
                val resources = TestResources()
                return TestRoots(
                    rootDir = rootDir,
                    context = object : ContextWrapper(Application()) {
                        override fun getFilesDir(): File = filesDir

                        override fun getCacheDir(): File = cacheDir

                        override fun getExternalFilesDir(type: String?): File = externalFilesDir

                        override fun getApplicationContext(): Context = this

                        override fun getPackageName(): String = "io.stamethyst.test"

                        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                            prefs.getOrPut(name) { InMemorySharedPreferences() }

                        override fun getResources(): Resources = resources
                    }
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private class TestResources : Resources(null, null, null) {
        override fun getString(id: Int): String = "res-$id"

        override fun getString(id: Int, vararg formatArgs: Any?): String =
            "res-$id ${formatArgs.joinToString()}"
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val values = LinkedHashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = synchronized(values) { LinkedHashMap(values) }

        override fun getString(key: String, defValue: String?): String? =
            synchronized(values) { values[key] as? String ?: defValue }

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            synchronized(values) { (values[key] as? Set<String>)?.toMutableSet() ?: defValues }

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

            override fun putString(key: String, value: String?): SharedPreferences.Editor =
                apply { pending[key] = value }

            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
                apply { pending[key] = values?.toMutableSet() }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor =
                apply { pending[key] = value }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor =
                apply { pending[key] = value }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
                apply { pending[key] = value }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
                apply { pending[key] = value }

            override fun remove(key: String): SharedPreferences.Editor = apply { removals += key }

            override fun clear(): SharedPreferences.Editor = apply { clear = true }

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
