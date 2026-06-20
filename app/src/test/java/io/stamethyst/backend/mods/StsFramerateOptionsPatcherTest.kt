package io.stamethyst.backend.mods

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode

class StsFramerateOptionsPatcherTest {
    @Test
    fun patchOptionsPanelClass_adds90FpsOption_andIsIdempotent() {
        val originalJar = resolveFixtureFile(
            "tools/desktop-1.0.jar",
            "../tools/desktop-1.0.jar"
        )
        assumeTrue(originalJar.isFile)

        val originalBytes = readJarEntry(originalJar, STS_PATCH_OPTIONS_PANEL_CLASS)
        assertFalse(StsFramerateOptionsPatcher.isPatchedOptionsPanelClass(originalBytes))
        assertEquals(
            listOf("24", "30", "60", "120", "240"),
            stringArrayFieldValues(originalBytes, "FRAMERATE_LABELS")
        )
        assertEquals(
            listOf(24, 30, 60, 120, 240),
            intArrayFieldValues(originalBytes, "FRAMERATE_OPTIONS")
        )

        val patchedBytes = StsFramerateOptionsPatcher.patchOptionsPanelClass(originalBytes)
        assertTrue(StsFramerateOptionsPatcher.isPatchedOptionsPanelClass(patchedBytes))
        assertEquals(
            listOf("24", "30", "60", "90", "120", "240"),
            stringArrayFieldValues(patchedBytes, "FRAMERATE_LABELS")
        )
        assertEquals(
            listOf(24, 30, 60, 90, 120, 240),
            intArrayFieldValues(patchedBytes, "FRAMERATE_OPTIONS")
        )

        val patchedAgain = StsFramerateOptionsPatcher.patchOptionsPanelClass(patchedBytes)
        assertArrayEquals(patchedBytes, patchedAgain)
    }

    private fun resolveFixtureFile(vararg candidates: String): File {
        return candidates
            .asSequence()
            .map(::File)
            .firstOrNull { it.isFile }
            ?: File(candidates.first())
    }

    private fun readJarEntry(jarFile: File, entryName: String): ByteArray {
        ZipFile(jarFile).use { zipFile ->
            val entry = zipFile.getEntry(entryName)
            requireNotNull(entry) { "Missing entry $entryName in ${jarFile.absolutePath}" }
            return JarFileIoUtils.readEntryBytes(zipFile, entry)
        }
    }

    private fun stringArrayFieldValues(classBytes: ByteArray, fieldName: String): List<String> {
        return arrayFieldValues(
            classBytes = classBytes,
            fieldName = fieldName,
            fieldDesc = "[Ljava/lang/String;",
            arrayKind = ArrayKind.STRING,
            storeOpcode = Opcodes.AASTORE
        ) { node -> (node as? LdcInsnNode)?.cst as? String }
    }

    private fun intArrayFieldValues(classBytes: ByteArray, fieldName: String): List<Int> {
        return arrayFieldValues(
            classBytes = classBytes,
            fieldName = fieldName,
            fieldDesc = "[I",
            arrayKind = ArrayKind.INT,
            storeOpcode = Opcodes.IASTORE
        ) { node -> intConstantValue(node) }
    }

    private enum class ArrayKind {
        STRING,
        INT
    }

    private fun <T> arrayFieldValues(
        classBytes: ByteArray,
        fieldName: String,
        fieldDesc: String,
        arrayKind: ArrayKind,
        storeOpcode: Int,
        parseValue: (AbstractInsnNode) -> T?
    ): List<T> {
        val constructor = optionsPanelConstructor(classBytes)
        val putField = findPutField(constructor, fieldName, fieldDesc)
        val arrayCreate = findArrayCreate(putField, arrayKind)
        val size = intConstantValue(previousMeaningful(arrayCreate.previous))
        requireNotNull(size) { "Missing array size for $fieldName" }
        val values = MutableList<T?>(size) { null }
        var current = nextMeaningful(arrayCreate.next)
        while (current != null && current !== putField) {
            if (current.opcode == storeOpcode) {
                val valueNode = requireNotNull(previousMeaningful(current.previous))
                val indexNode = requireNotNull(previousMeaningful(valueNode.previous))
                val index = intConstantValue(indexNode)
                requireNotNull(index) { "Missing array index for $fieldName" }
                values[index] = parseValue(valueNode)
            }
            current = nextMeaningful(current.next)
        }
        require(current === putField) { "PUTFIELD not reached for $fieldName" }
        require(values.all { value -> value != null }) { "Incomplete values for $fieldName" }
        @Suppress("UNCHECKED_CAST")
        return values as List<T>
    }

    private fun optionsPanelConstructor(classBytes: ByteArray): MethodNode {
        val classNode = ClassNode()
        ClassReader(classBytes).accept(classNode, ClassReader.SKIP_FRAMES)
        return requireNotNull(
            classNode.methods.firstOrNull { method ->
                method.name == "<init>" && method.desc == "()V"
            }
        )
    }

    private fun findPutField(
        method: MethodNode,
        fieldName: String,
        fieldDesc: String
    ): FieldInsnNode {
        var current = method.instructions.first
        while (current != null) {
            val field = current as? FieldInsnNode
            if (field != null &&
                field.opcode == Opcodes.PUTFIELD &&
                field.name == fieldName &&
                field.desc == fieldDesc
            ) {
                return field
            }
            current = current.next
        }
        error("Missing PUTFIELD for $fieldName")
    }

    private fun findArrayCreate(putField: FieldInsnNode, arrayKind: ArrayKind): AbstractInsnNode {
        var current = previousMeaningful(putField.previous)
        while (current != null) {
            if (isArrayCreate(current, arrayKind)) {
                return current
            }
            current = previousMeaningful(current.previous)
        }
        error("Missing array creation for ${putField.name}")
    }

    private fun isArrayCreate(node: AbstractInsnNode, arrayKind: ArrayKind): Boolean {
        return when (arrayKind) {
            ArrayKind.STRING -> {
                val typeNode = node as? TypeInsnNode
                typeNode != null &&
                    typeNode.opcode == Opcodes.ANEWARRAY &&
                    typeNode.desc == "java/lang/String"
            }
            ArrayKind.INT -> {
                val intNode = node as? IntInsnNode
                intNode != null &&
                    intNode.opcode == Opcodes.NEWARRAY &&
                    intNode.operand == Opcodes.T_INT
            }
        }
    }

    private fun intConstantValue(node: AbstractInsnNode?): Int? {
        return when (node) {
            is InsnNode -> when (node.opcode) {
                Opcodes.ICONST_M1 -> -1
                Opcodes.ICONST_0 -> 0
                Opcodes.ICONST_1 -> 1
                Opcodes.ICONST_2 -> 2
                Opcodes.ICONST_3 -> 3
                Opcodes.ICONST_4 -> 4
                Opcodes.ICONST_5 -> 5
                else -> null
            }
            is IntInsnNode -> when (node.opcode) {
                Opcodes.BIPUSH, Opcodes.SIPUSH -> node.operand
                else -> null
            }
            is LdcInsnNode -> node.cst as? Int
            else -> null
        }
    }

    private fun previousMeaningful(node: AbstractInsnNode?): AbstractInsnNode? {
        var current = node
        while (current != null) {
            if (current.opcode >= 0) {
                return current
            }
            current = current.previous
        }
        return null
    }

    private fun nextMeaningful(node: AbstractInsnNode?): AbstractInsnNode? {
        var current = node
        while (current != null) {
            if (current.opcode >= 0) {
                return current
            }
            current = current.next
        }
        return null
    }
}
