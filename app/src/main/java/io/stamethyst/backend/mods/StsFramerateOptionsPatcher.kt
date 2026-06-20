package io.stamethyst.backend.mods

import java.io.IOException
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode

internal object StsFramerateOptionsPatcher {
    private const val OPTIONS_PANEL_INTERNAL_NAME =
        "com/megacrit/cardcrawl/screens/options/OptionsPanel"
    private const val OPTIONS_PANEL_CONSTRUCTOR_DESC = "()V"
    private const val FRAMERATE_LABELS_FIELD_NAME = "FRAMERATE_LABELS"
    private const val FRAMERATE_OPTIONS_FIELD_NAME = "FRAMERATE_OPTIONS"
    private const val STRING_ARRAY_DESC = "[Ljava/lang/String;"
    private const val INT_ARRAY_DESC = "[I"
    private const val STRING_INTERNAL_NAME = "java/lang/String"
    private val VANILLA_FRAMERATE_LABELS = listOf("24", "30", "60", "120", "240")
    private val VANILLA_FRAMERATE_OPTIONS = listOf(24, 30, 60, 120, 240)
    private val PATCHED_FRAMERATE_LABELS = listOf("24", "30", "60", "90", "120", "240")
    private val PATCHED_FRAMERATE_OPTIONS = listOf(24, 30, 60, 90, 120, 240)

    @Throws(IOException::class)
    fun patchOptionsPanelClass(classBytes: ByteArray): ByteArray {
        val classNode = readClassNode(classBytes)
        if (classNode.name != OPTIONS_PANEL_INTERNAL_NAME) {
            throw IOException("Unexpected options panel class: ${classNode.name}")
        }
        val constructor = classNode.methods.firstOrNull { method ->
            method.name == "<init>" && method.desc == OPTIONS_PANEL_CONSTRUCTOR_DESC
        } ?: throw IOException("Unsupported desktop-1.0.jar: OptionsPanel() constructor not found")

        if (isPatchedConstructor(constructor)) {
            return classBytes
        }

        val labelAssignment = findStringArrayAssignment(
            constructor,
            FRAMERATE_LABELS_FIELD_NAME,
            STRING_ARRAY_DESC
        ) ?: throw IOException("Unsupported desktop-1.0.jar: framerate label options not found")
        val fpsAssignment = findIntArrayAssignment(
            constructor,
            FRAMERATE_OPTIONS_FIELD_NAME,
            INT_ARRAY_DESC
        ) ?: throw IOException("Unsupported desktop-1.0.jar: framerate numeric options not found")

        if (labelAssignment.values != VANILLA_FRAMERATE_LABELS) {
            throw IOException(
                "Unsupported desktop-1.0.jar: unexpected framerate labels ${labelAssignment.values}"
            )
        }
        if (fpsAssignment.values != VANILLA_FRAMERATE_OPTIONS) {
            throw IOException(
                "Unsupported desktop-1.0.jar: unexpected framerate options ${fpsAssignment.values}"
            )
        }

        replaceInstructions(
            constructor.instructions,
            labelAssignment.start,
            labelAssignment.end,
            buildStringArrayAssignment(
                FRAMERATE_LABELS_FIELD_NAME,
                STRING_ARRAY_DESC,
                PATCHED_FRAMERATE_LABELS
            )
        )
        replaceInstructions(
            constructor.instructions,
            fpsAssignment.start,
            fpsAssignment.end,
            buildIntArrayAssignment(
                FRAMERATE_OPTIONS_FIELD_NAME,
                INT_ARRAY_DESC,
                PATCHED_FRAMERATE_OPTIONS
            )
        )

        return writeClass(classNode)
    }

    fun isPatchedOptionsPanelClass(classBytes: ByteArray): Boolean {
        val classNode = readClassNode(classBytes)
        if (classNode.name != OPTIONS_PANEL_INTERNAL_NAME) {
            return false
        }
        val constructor = classNode.methods.firstOrNull { method ->
            method.name == "<init>" && method.desc == OPTIONS_PANEL_CONSTRUCTOR_DESC
        } ?: return false
        return isPatchedConstructor(constructor)
    }

    private fun isPatchedConstructor(constructor: MethodNode): Boolean {
        val labels = findStringArrayAssignment(
            constructor,
            FRAMERATE_LABELS_FIELD_NAME,
            STRING_ARRAY_DESC
        )?.values ?: return false
        val options = findIntArrayAssignment(
            constructor,
            FRAMERATE_OPTIONS_FIELD_NAME,
            INT_ARRAY_DESC
        )?.values ?: return false
        return labels == PATCHED_FRAMERATE_LABELS && options == PATCHED_FRAMERATE_OPTIONS
    }

    private data class ArrayAssignment<T>(
        val start: AbstractInsnNode,
        val end: FieldInsnNode,
        val values: List<T>
    )

    private fun findStringArrayAssignment(
        method: MethodNode,
        fieldName: String,
        fieldDesc: String
    ): ArrayAssignment<String>? {
        val putField = findArrayPutField(method, fieldName, fieldDesc) ?: return null
        val arrayStart = findArrayStart(putField, ArrayKind.STRING) ?: return null
        val values = readArrayValues<String>(
            arrayStart.arrayCreate,
            putField,
            Opcodes.AASTORE
        ) { node -> (node as? LdcInsnNode)?.cst as? String } ?: return null
        return ArrayAssignment(arrayStart.thisLoad, putField, values)
    }

    private fun findIntArrayAssignment(
        method: MethodNode,
        fieldName: String,
        fieldDesc: String
    ): ArrayAssignment<Int>? {
        val putField = findArrayPutField(method, fieldName, fieldDesc) ?: return null
        val arrayStart = findArrayStart(putField, ArrayKind.INT) ?: return null
        val values = readArrayValues<Int>(
            arrayStart.arrayCreate,
            putField,
            Opcodes.IASTORE
        ) { node -> intConstantValue(node) } ?: return null
        return ArrayAssignment(arrayStart.thisLoad, putField, values)
    }

    private fun findArrayPutField(
        method: MethodNode,
        fieldName: String,
        fieldDesc: String
    ): FieldInsnNode? {
        var current = method.instructions.first
        while (current != null) {
            val field = current as? FieldInsnNode
            if (field != null &&
                field.opcode == Opcodes.PUTFIELD &&
                field.owner == OPTIONS_PANEL_INTERNAL_NAME &&
                field.name == fieldName &&
                field.desc == fieldDesc
            ) {
                return field
            }
            current = current.next
        }
        return null
    }

    private enum class ArrayKind {
        STRING,
        INT
    }

    private data class ArrayStart(
        val thisLoad: VarInsnNode,
        val arrayCreate: AbstractInsnNode,
        val size: Int
    )

    private fun findArrayStart(putField: FieldInsnNode, kind: ArrayKind): ArrayStart? {
        var current = previousMeaningful(putField.previous)
        while (current != null) {
            if (isArrayCreate(current, kind)) {
                val sizeNode = previousMeaningful(current.previous) ?: return null
                val thisLoad = previousMeaningful(sizeNode.previous) as? VarInsnNode ?: return null
                val size = intConstantValue(sizeNode) ?: return null
                if (thisLoad.opcode != Opcodes.ALOAD || thisLoad.`var` != 0) {
                    return null
                }
                return ArrayStart(thisLoad, current, size)
            }
            current = previousMeaningful(current.previous)
        }
        return null
    }

    private fun isArrayCreate(node: AbstractInsnNode, kind: ArrayKind): Boolean {
        return when (kind) {
            ArrayKind.STRING -> {
                val typeNode = node as? TypeInsnNode
                typeNode != null &&
                    typeNode.opcode == Opcodes.ANEWARRAY &&
                    typeNode.desc == STRING_INTERNAL_NAME
            }
            ArrayKind.INT -> {
                val intNode = node as? IntInsnNode
                intNode != null &&
                    intNode.opcode == Opcodes.NEWARRAY &&
                    intNode.operand == Opcodes.T_INT
            }
        }
    }

    private fun <T> readArrayValues(
        arrayCreate: AbstractInsnNode,
        putField: FieldInsnNode,
        storeOpcode: Int,
        parseValue: (AbstractInsnNode) -> T?
    ): List<T>? {
        val size = intConstantValue(previousMeaningful(arrayCreate.previous)) ?: return null
        val values = MutableList<T?>(size) { null }
        var current = nextMeaningful(arrayCreate.next)
        while (current != null && current !== putField) {
            val store = current
            if (store.opcode == storeOpcode) {
                val valueNode = previousMeaningful(store.previous) ?: return null
                val indexNode = previousMeaningful(valueNode.previous) ?: return null
                val dupNode = previousMeaningful(indexNode.previous) ?: return null
                val index = intConstantValue(indexNode) ?: return null
                val value = parseValue(valueNode) ?: return null
                if (dupNode.opcode != Opcodes.DUP || index !in values.indices) {
                    return null
                }
                values[index] = value
            }
            current = nextMeaningful(current.next)
        }
        if (current !== putField || values.any { value -> value == null }) {
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return values as List<T>
    }

    private fun buildStringArrayAssignment(
        fieldName: String,
        fieldDesc: String,
        values: List<String>
    ): InsnList {
        return InsnList().apply {
            add(VarInsnNode(Opcodes.ALOAD, 0))
            add(pushInt(values.size))
            add(TypeInsnNode(Opcodes.ANEWARRAY, STRING_INTERNAL_NAME))
            values.forEachIndexed { index, value ->
                add(InsnNode(Opcodes.DUP))
                add(pushInt(index))
                add(LdcInsnNode(value))
                add(InsnNode(Opcodes.AASTORE))
            }
            add(FieldInsnNode(Opcodes.PUTFIELD, OPTIONS_PANEL_INTERNAL_NAME, fieldName, fieldDesc))
        }
    }

    private fun buildIntArrayAssignment(
        fieldName: String,
        fieldDesc: String,
        values: List<Int>
    ): InsnList {
        return InsnList().apply {
            add(VarInsnNode(Opcodes.ALOAD, 0))
            add(pushInt(values.size))
            add(IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT))
            values.forEachIndexed { index, value ->
                add(InsnNode(Opcodes.DUP))
                add(pushInt(index))
                add(pushInt(value))
                add(InsnNode(Opcodes.IASTORE))
            }
            add(FieldInsnNode(Opcodes.PUTFIELD, OPTIONS_PANEL_INTERNAL_NAME, fieldName, fieldDesc))
        }
    }

    private fun replaceInstructions(
        instructions: InsnList,
        start: AbstractInsnNode,
        end: AbstractInsnNode,
        replacement: InsnList
    ) {
        instructions.insertBefore(start, replacement)
        var current = start
        while (true) {
            val next = current.next
            instructions.remove(current)
            if (current === end) {
                break
            }
            current = next
                ?: throw IOException("Unsupported desktop-1.0.jar: framerate assignment end not reachable")
        }
    }

    private fun pushInt(value: Int): AbstractInsnNode {
        return when (value) {
            -1 -> InsnNode(Opcodes.ICONST_M1)
            0 -> InsnNode(Opcodes.ICONST_0)
            1 -> InsnNode(Opcodes.ICONST_1)
            2 -> InsnNode(Opcodes.ICONST_2)
            3 -> InsnNode(Opcodes.ICONST_3)
            4 -> InsnNode(Opcodes.ICONST_4)
            5 -> InsnNode(Opcodes.ICONST_5)
            in Byte.MIN_VALUE..Byte.MAX_VALUE -> IntInsnNode(Opcodes.BIPUSH, value)
            in Short.MIN_VALUE..Short.MAX_VALUE -> IntInsnNode(Opcodes.SIPUSH, value)
            else -> LdcInsnNode(value)
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

    private fun readClassNode(classBytes: ByteArray): ClassNode {
        val classNode = ClassNode()
        ClassReader(classBytes).accept(classNode, 0)
        return classNode
    }

    private fun writeClass(classNode: ClassNode): ByteArray {
        val classWriter = ClassWriter(0)
        classNode.accept(classWriter)
        return classWriter.toByteArray()
    }
}
