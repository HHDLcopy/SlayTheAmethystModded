package io.stamethyst.backend.launch

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class JreLauncherSignalPolicyTest {
    @Test
    fun jvmLaunchPreservesAndroidRuntimeSignalHandlers() {
        val source = readSource("app/src/main/jni/jre_launcher.c")
        val reservedSignalGuard =
            source.indexOf("sigid >= __SIGRTMIN && sigid <= __SIGRTMIN + 9")
        val resetSignalHandler = source.indexOf("sigaction(sigid, &clean_sa, NULL)")

        assertTrue(reservedSignalGuard >= 0)
        assertTrue(resetSignalHandler > reservedSignalGuard)
        assertTrue(source.contains("continue;"))
        assertTrue(source.contains("const static int tracked_signals[]"))
        assertTrue(source.contains("abort_waiter_setup();"))
    }

    private fun readSource(path: String): String {
        val file = listOf(File(path), File("..", path))
            .firstOrNull(File::isFile)
            ?: File(path)
        assertTrue("Missing source file: ${file.absolutePath}", file.isFile)
        return file.readText()
    }
}
