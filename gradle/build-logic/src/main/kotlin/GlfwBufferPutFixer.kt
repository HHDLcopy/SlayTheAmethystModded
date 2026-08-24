import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodInsnNode
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Fixes the LWJGL3 GLFW Java stub (packaged as `lwjgl-glfw-classes-base.jar`).
 *
 * Several GLFW getters write their results with a *relative* `IntBuffer.put(int)`
 * (or `PointerBuffer.put(long)`), which advances the buffer position. libGDX 1.9.5
 * reuses a single capacity-1 buffer across consecutive calls
 * (`Lwjgl3Graphics.updateFramebufferInfo` calls `glfwGetFramebufferSize` then
 * `glfwGetWindowSize` on the same `tmpBuffer`), so the second relative put hits
 * `position == limit == 1` and throws `java.nio.BufferOverflowException` while
 * creating the window — the "stuck at 96%" launcher bug.
 *
 * This rewrites those relative puts to absolute `put(index, value)` writes at
 * index 0, which matches libGDX's buffer-reuse contract and the already-correct
 * `glfwGetMonitorContentScale` implementation in the same class.
 */
object GlfwBufferPutFixer {

    private const val INT_BUFFER_PUT_RELATIVE = "(I)Ljava/nio/IntBuffer;"
    private const val INT_BUFFER_PUT_ABSOLUTE = "(II)Ljava/nio/IntBuffer;"
    private const val POINTER_BUFFER_PUT_RELATIVE = "(J)Lorg/lwjgl/PointerBuffer;"
    private const val POINTER_BUFFER_PUT_ABSOLUTE = "(IJ)Lorg/lwjgl/PointerBuffer;"

    private const val INT_BUFFER_OWNER = "java/nio/IntBuffer"
    private const val POINTER_BUFFER_OWNER = "org/lwjgl/PointerBuffer"
    private const val PUT_METHOD_NAME = "put"

    private const val GLFW_CLASS_ENTRY = "org/lwjgl/glfw/GLFW.class"

    /**
     * Rewrites [inputJar] to [outputJar], applying the relative-put fix to the
     * GLFW stub class. All other entries are copied byte-for-byte.
     */
    fun fixJar(inputJar: File, outputJar: File) {
        JarInputStream(FileInputStream(inputJar)).use { input ->
            JarOutputStream(FileOutputStream(outputJar)).use { output ->
                var entry: JarEntry? = input.nextJarEntry
                while (entry != null) {
                    val name = entry.name
                    if (name == GLFW_CLASS_ENTRY) {
                        val bytes = input.readBytes()
                        output.putNextEntry(JarEntry(name))
                        output.write(fixGlfwClass(bytes))
                    } else {
                        output.putNextEntry(JarEntry(name))
                        input.copyTo(output)
                    }
                    output.closeEntry()
                    input.closeEntry()
                    entry = input.nextJarEntry
                }
            }
        }
    }

    private fun fixGlfwClass(classBytes: ByteArray): ByteArray {
        val classNode = ClassNode()
        ClassReader(classBytes).accept(classNode, 0)

        var changed = false
        for (method in classNode.methods) {
            var insn: AbstractInsnNode? = method.instructions.first
            while (insn != null) {
                val methodInsn = insn as? MethodInsnNode
                if (methodInsn != null && methodInsn.name == PUT_METHOD_NAME) {
                    val fixed = when {
                        methodInsn.owner == INT_BUFFER_OWNER &&
                            methodInsn.desc == INT_BUFFER_PUT_RELATIVE -> {
                            methodInsn.desc = INT_BUFFER_PUT_ABSOLUTE
                            true
                        }
                        methodInsn.owner == POINTER_BUFFER_OWNER &&
                            methodInsn.desc == POINTER_BUFFER_PUT_RELATIVE -> {
                            methodInsn.desc = POINTER_BUFFER_PUT_ABSOLUTE
                            true
                        }
                        else -> false
                    }
                    if (fixed) {
                        // Stack before the call is [buffer, value]. The absolute
                        // form needs [buffer, index(0), value], so inject ICONST_0
                        // before the instruction that produces the value (the
                        // instruction immediately preceding the put call).
                        val valueProducer = insn.previous
                        if (valueProducer != null) {
                            method.instructions.insertBefore(
                                valueProducer,
                                InsnNode(Opcodes.ICONST_0)
                            )
                        }
                        changed = true
                    }
                }
                insn = insn.next
            }
        }

        if (!changed) {
            return classBytes
        }

        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        classNode.accept(writer)
        return writer.toByteArray()
    }
}
