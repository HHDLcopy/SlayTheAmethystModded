package io.stamethyst.backend.mods.importing.patches.mods.firstperson

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode

class FirstPersonViewImportCompatPatcherTest {
    @Test
    fun patchInPlace_rewritesOnlyRendererCursorReadsAndIsIdempotent() {
        val jarPath = Files.createTempFile("firstperson-view", ".jar")
        try {
            ZipOutputStream(Files.newOutputStream(jarPath)).use { zipOut ->
                zipOut.putNextEntry(ZipEntry("sts/fps/renderer/FirstPersonRenderer.class"))
                zipOut.write(rendererClassBytes())
                zipOut.closeEntry()
            }

            val firstPatch = FirstPersonViewImportCompatPatcher.patchInPlace(jarPath.toFile())
            assertEquals(1, firstPatch.patchedClassEntries)
            assertEquals(1, firstPatch.patchedYawInputCalls)
            assertEquals(1, firstPatch.patchedPitchInputCalls)

            val classNode = readRendererClass(jarPath.toFile())
            val update = classNode.methods.first { it.name == "update" && it.desc == "()V" }
            val calls = update.instructions.toArray().filterIsInstance<MethodInsnNode>()
            assertFalse(calls.any {
                it.owner == "com/badlogic/gdx/Input" &&
                    (it.name == "getX" || it.name == "getY")
            })
            assertEquals(1, calls.count {
                it.owner == "io/stamethyst/bridge/FirstPersonGyroBridge" &&
                    it.name == "getCursorX" && it.desc == "(Ljava/lang/Object;)I"
            })
            assertEquals(1, calls.count {
                it.owner == "io/stamethyst/bridge/FirstPersonGyroBridge" &&
                    it.name == "getCursorY" && it.desc == "(Ljava/lang/Object;)I"
            })

            val secondPatch = FirstPersonViewImportCompatPatcher.patchInPlace(jarPath.toFile())
            assertEquals(0, secondPatch.patchedClassEntries)
            assertEquals(0, secondPatch.patchedYawInputCalls)
            assertEquals(0, secondPatch.patchedPitchInputCalls)
        } finally {
            Files.deleteIfExists(jarPath)
        }
    }

    @Test
    fun patchInPlace_returnsZeroesWhenRendererClassIsMissing() {
        val jarPath = Files.createTempFile("firstperson-view-missing", ".jar")
        try {
            ZipOutputStream(Files.newOutputStream(jarPath)).use { zipOut ->
                zipOut.putNextEntry(ZipEntry("other.class"))
                zipOut.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
                zipOut.closeEntry()
            }
            val result = FirstPersonViewImportCompatPatcher.patchInPlace(jarPath.toFile())
            assertEquals(0, result.patchedClassEntries)
            assertFalse(result.hasAnyPatch)
        } finally {
            Files.deleteIfExists(jarPath)
        }
    }

    private fun readRendererClass(jarFile: java.io.File): ClassNode {
        ZipFile(jarFile).use { zipFile ->
            val bytes = zipFile.getInputStream(
                zipFile.getEntry("sts/fps/renderer/FirstPersonRenderer.class")
            ).use { it.readBytes() }
            val classNode = ClassNode()
            ClassReader(bytes).accept(classNode, 0)
            return classNode
        }
    }

    private fun rendererClassBytes(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "sts/fps/renderer/FirstPersonRenderer", null, "java/lang/Object", null)
        val method = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "update", "()V", null, null)
        method.visitCode()
        method.visitFieldInsn(
            Opcodes.GETSTATIC,
            "com/badlogic/gdx/Gdx",
            "input",
            "Lcom/badlogic/gdx/Input;"
        )
        method.visitMethodInsn(
            Opcodes.INVOKEINTERFACE,
            "com/badlogic/gdx/Input",
            "getX",
            "()I",
            true
        )
        method.visitInsn(Opcodes.POP)
        method.visitFieldInsn(
            Opcodes.GETSTATIC,
            "com/badlogic/gdx/Gdx",
            "input",
            "Lcom/badlogic/gdx/Input;"
        )
        method.visitMethodInsn(
            Opcodes.INVOKEINTERFACE,
            "com/badlogic/gdx/Input",
            "getY",
            "()I",
            true
        )
        method.visitInsn(Opcodes.POP)
        method.visitInsn(Opcodes.RETURN)
        method.visitMaxs(0, 0)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }
}
