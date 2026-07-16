package io.stamethyst.input

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InputBridgeCursorStateContractTest {
    @Test
    fun cursorStateIsStoredBeforeDirectCallbackDelivery() {
        val source = readSource("app/src/main/jni/input_bridge_v3.c")
        val function = source.substringAfter("void critical_send_cursor_pos(jfloat x, jfloat y)")
            .substringBefore("void noncritical_send_cursor_pos")

        val storeX = function.indexOf("pojav_environ->cursorX = x;")
        val storeY = function.indexOf("pojav_environ->cursorY = y;")
        val callbackCheck = function.indexOf("pojav_environ->GLFW_invoke_CursorPos")
        val callbackInvoke = function.lastIndexOf("pojav_environ->GLFW_invoke_CursorPos")
        val directLastX = function.indexOf("pojav_environ->cLastX = x;")
        val directLastY = function.indexOf("pojav_environ->cLastY = y;")

        assertTrue("cursor X must be stored", storeX >= 0)
        assertTrue("cursor Y must be stored", storeY >= 0)
        assertTrue("cursor state must be stored before callback dispatch", storeX < callbackCheck)
        assertTrue("cursor state must be stored before callback dispatch", storeY < callbackCheck)
        assertTrue("direct X delivery must advance the pump snapshot", directLastX >= 0)
        assertTrue("direct Y delivery must advance the pump snapshot", directLastY >= 0)
        assertTrue(directLastX < callbackInvoke)
        assertTrue(directLastY < callbackInvoke)
    }

    private fun readSource(path: String): String {
        val file = listOf(File(path), File("..", path)).firstOrNull(File::isFile) ?: File(path)
        assertTrue("Missing source file: ${file.absolutePath}", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }
}
