package io.stamethyst.backend.mods

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StsDesktopJarIntegrityTest {
    @Test
    fun sha1Hex_matchesKnownDigest() {
        val tempFile = Files.createTempFile("sts-sha1", ".jar").toFile()
        tempFile.writeText("abc", StandardCharsets.UTF_8)

        assertEquals(
            "a9993e364706816aba3e25717850c26c9cd0d89d",
            StsDesktopJarIntegrity.sha1Hex(tempFile)
        )
    }

    @Test
    fun inspect_matchesExpected_ignoreCase() {
        val result = StsDesktopJarIntegrity.Result(
            expectedSha1 = "ABCDEF",
            actualSha1 = "abcdef"
        )

        assertTrue(result.matchesExpected)
    }
}
