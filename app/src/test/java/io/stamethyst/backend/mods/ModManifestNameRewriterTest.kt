package io.stamethyst.backend.mods

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipFile

class ModManifestNameRewriterTest {
    @Test
    fun rewriteNameInPlace_updatesManifestNameAndInvalidatesManifestCache() {
        val jarFile = tempJar("RenameMe.jar")
        writeJar(
            jarFile,
            "ModTheSpire.json" to """
                {
                  "modid": "rename-me",
                  "name": "Old Name",
                  "version": "1.0"
                }
            """.trimIndent()
        )

        assertEquals("Old Name", ModJarSupport.readModManifest(jarFile).name)

        val result = ModManifestNameRewriter.rewriteNameInPlace(jarFile, "New Name")

        assertTrue(result.changed)
        assertEquals("Old Name", result.previousName)
        assertEquals("New Name", result.newName)
        assertEquals("New Name", ModJarSupport.readModManifest(jarFile).name)
    }

    @Test
    fun rewriteNameInPlace_keepsExistingNameKeyVariant() {
        val jarFile = tempJar("VariantName.jar")
        writeJar(
            jarFile,
            "ModTheSpire.json" to """
                {
                  "modid": "variant-name",
                  "modName": "Old Variant Name"
                }
            """.trimIndent()
        )

        ModManifestNameRewriter.rewriteNameInPlace(jarFile, "New Variant Name")

        val manifest = JSONObject(readEntry(jarFile, "ModTheSpire.json"))
        assertTrue(manifest.has("modName"))
        assertFalse(manifest.has("name"))
        assertEquals("New Variant Name", manifest.getString("modName"))
        assertEquals("New Variant Name", ModJarSupport.readModManifest(jarFile).name)
    }

    @Test
    fun rewriteNameInPlace_updatesFirstObjectInArrayManifest() {
        val jarFile = tempJar("ArrayManifest.jar")
        writeJar(
            jarFile,
            "ModTheSpire.json" to """
                [
                  {
                    "modid": "array-manifest",
                    "name": "Old Array Name"
                  }
                ]
            """.trimIndent()
        )

        ModManifestNameRewriter.rewriteNameInPlace(jarFile, "New Array Name")

        val manifestArray = JSONArray(readEntry(jarFile, "ModTheSpire.json"))
        assertEquals("New Array Name", manifestArray.getJSONObject(0).getString("name"))
        assertEquals("New Array Name", ModJarSupport.readModManifest(jarFile).name)
    }

    @Test
    fun rewriteNameInPlace_reportsRewriteProgress() {
        val jarFile = tempJar("Progress.jar")
        writeJar(
            jarFile,
            "ModTheSpire.json" to """
                {
                  "modid": "progress",
                  "name": "Old Progress Name"
                }
            """.trimIndent(),
            "assets/large.txt" to "progress-payload\n".repeat(8192)
        )
        val originalLength = jarFile.length()
        val progressValues = ArrayList<Long>()

        ModManifestNameRewriter.rewriteNameInPlace(jarFile, "New Progress Name") { bytesRead ->
            progressValues += bytesRead
        }

        assertTrue(progressValues.isNotEmpty())
        assertEquals(0L, progressValues.first())
        assertEquals(originalLength, progressValues.last())
        progressValues.zipWithNext().forEach { (previous, next) ->
            assertTrue("progress moved backwards: $previous -> $next", next >= previous)
        }
    }

    @Test(expected = IOException::class)
    fun rewriteNameInPlace_failsWhenManifestMissing() {
        val jarFile = tempJar("NoManifest.jar")
        writeJar(
            jarFile,
            "example/Example.class" to "class-bytes"
        )

        ModManifestNameRewriter.rewriteNameInPlace(jarFile, "New Name")
    }

    private fun tempJar(name: String): File {
        return Files.createTempDirectory("mod-name-rewriter").resolve(name).toFile()
    }

    private fun writeJar(jarFile: File, vararg entries: Pair<String, String>) {
        ZipArchiveOutputStream(jarFile).use { zipOut ->
            entries.forEach { (entryName, text) ->
                val entry = ZipArchiveEntry(entryName)
                zipOut.putArchiveEntry(entry)
                zipOut.write(text.toByteArray(StandardCharsets.UTF_8))
                zipOut.closeArchiveEntry()
            }
        }
    }

    private fun readEntry(jarFile: File, entryName: String): String {
        ZipFile(jarFile).use { zipFile ->
            val entry = zipFile.getEntry(entryName)
            return JarFileIoUtils.readEntry(zipFile, entry)
        }
    }
}
