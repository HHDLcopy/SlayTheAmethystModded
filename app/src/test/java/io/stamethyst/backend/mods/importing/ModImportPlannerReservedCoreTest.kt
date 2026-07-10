package io.stamethyst.backend.mods.importing

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Resources
import io.stamethyst.backend.mods.CompatibilitySettings
import io.stamethyst.backend.mods.ImportedModPatchRegistry
import io.stamethyst.backend.mods.ReservedCoreModComponents
import io.stamethyst.backend.mods.importing.patches.mods.chaofanmod.ChaofanModImportPatchModule
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
