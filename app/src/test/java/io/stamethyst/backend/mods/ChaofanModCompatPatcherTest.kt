package io.stamethyst.backend.mods

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode

class ChaofanModCompatPatcherTest {
    @Test
    fun patchInPlace_removesSteamworksHelperSubscriptionAndIsIdempotent() {
        val tempDir = Files.createTempDirectory("chaofanmod-patcher-test")
        val jarFile = tempDir.resolve("chaofanmod.jar").toFile()
        createChaofanJar(jarFile)

        assertTrue(hasSteamworksHelperSubscription(jarFile))

        val firstPatch = ChaofanModCompatPatcher.patchInPlace(jarFile)
        assertTrue(firstPatch.patchedSteamworksHelperInitialization)
        assertTrue(firstPatch.hasAnyPatch)
        assertFalse(hasSteamworksHelperSubscription(jarFile))
        assertTrue(hasConsoleCommandRegistration(jarFile))

        val secondPatch = ChaofanModCompatPatcher.patchInPlace(jarFile)
        assertFalse(secondPatch.patchedSteamworksHelperInitialization)
        assertFalse(secondPatch.hasAnyPatch)
        assertFalse(hasSteamworksHelperSubscription(jarFile))
        assertTrue(hasConsoleCommandRegistration(jarFile))
    }

    @Test
    fun patchInPlace_returnsFalseWhenTargetClassIsMissing() {
        val tempDir = Files.createTempDirectory("chaofanmod-patcher-empty")
        val jarFile = tempDir.resolve("OtherMod.jar").toFile()
        ZipOutputStream(jarFile.outputStream()).use { zipOut ->
            zipOut.putNextEntry(ZipEntry("example/Placeholder.class"))
            zipOut.write(byteArrayOf(0x00))
            zipOut.closeEntry()
        }

        val patchResult = ChaofanModCompatPatcher.patchInPlace(jarFile)
        assertFalse(patchResult.patchedSteamworksHelperInitialization)
        assertFalse(patchResult.hasAnyPatch)
    }

    private fun createChaofanJar(jarFile: File) {
        ZipOutputStream(jarFile.outputStream()).use { zipOut ->
            zipOut.putNextEntry(ZipEntry("io/chaofan/sts/chaofanmod/ChaofanMod.class"))
            zipOut.write(buildChaofanModClassBytes())
            zipOut.closeEntry()
        }
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
            visitLdcInsn("chaofanmod")
            visitInsn(Opcodes.ACONST_NULL)
            visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "basemod/devcommands/ConsoleCommand",
                "addCommand",
                "(Ljava/lang/String;Ljava/lang/Class;)V",
                false
            )
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }

        classWriter.visitEnd()
        return classWriter.toByteArray()
    }

    private fun hasSteamworksHelperSubscription(jarFile: File): Boolean {
        val method = readReceivePostInitialize(jarFile)
        return meaningfulInstructions(method).any { node ->
            (node is TypeInsnNode &&
                node.opcode == Opcodes.NEW &&
                node.desc == "io/chaofan/sts/chaofanmod/utils/SteamworksHelper") ||
                (node is FieldInsnNode &&
                    node.owner == "io/chaofan/sts/chaofanmod/ChaofanMod" &&
                    node.name == "steamworksHelper") ||
                (node is MethodInsnNode &&
                    node.owner == "basemod/BaseMod" &&
                    node.name == "subscribe")
        }
    }

    private fun hasConsoleCommandRegistration(jarFile: File): Boolean {
        val method = readReceivePostInitialize(jarFile)
        return meaningfulInstructions(method).any { node ->
            node is MethodInsnNode &&
                node.owner == "basemod/devcommands/ConsoleCommand" &&
                node.name == "addCommand" &&
                node.desc == "(Ljava/lang/String;Ljava/lang/Class;)V"
        }
    }

    private fun readReceivePostInitialize(jarFile: File): MethodNode {
        val classBytes = JarFileIoUtils.readJarEntryBytes(
            jarFile,
            "io/chaofan/sts/chaofanmod/ChaofanMod.class"
        )
        assertNotNull(classBytes)
        val classNode = org.objectweb.asm.tree.ClassNode()
        ClassReader(classBytes!!).accept(classNode, 0)
        val method = classNode.methods.firstOrNull { candidate ->
            candidate.name == "receivePostInitialize" && candidate.desc == "()V"
        }
        assertNotNull(method)
        return method!!
    }

    private fun meaningfulInstructions(method: MethodNode): List<AbstractInsnNode> {
        val result = ArrayList<AbstractInsnNode>()
        var current: AbstractInsnNode? = method.instructions.first
        while (current != null) {
            if (current !is LabelNode && current !is LineNumberNode && current !is FrameNode) {
                result += current
            }
            current = current.next
        }
        return result
    }
}
