package io.stamethyst.backend.mods

import io.stamethyst.backend.mods.importing.patches.mods.ori.OriImportCompatPatcher
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OriImportCompatPatcherTest {
    @Test
    fun patchInPlace_rewritesOriBlurShadersAndIsIdempotent() {
        val tempDir = Files.createTempDirectory("ori-import-patcher-test")
        val jarFile = tempDir.resolve("Ori.jar").toFile()
        createJar(
            jarFile,
            mapOf(
                "oriShaders/gaussianBlur/fragment.glsl" to legacyGaussianBlurFragment,
                "oriShaders/boxBlur/fragment.glsl" to legacyBoxBlurFragment,
                "oriShaders/boxBlur/vertex.glsl" to "void main() {}\n"
            )
        )

        val firstPatch = OriImportCompatPatcher.patchInPlace(jarFile)
        assertEquals(2, firstPatch.patchedShaderEntries)
        assertEquals(1, firstPatch.patchedGaussianBlurShaderEntries)
        assertEquals(1, firstPatch.patchedBoxBlurShaderEntries)
        assertEquals(103, firstPatch.estimatedTextureSamplesBefore)
        assertEquals(19, firstPatch.estimatedTextureSamplesAfter)
        assertTrue(firstPatch.hasAnyPatch)

        val gaussianSource = readJarText(jarFile, "oriShaders/gaussianBlur/fragment.glsl")
        val boxSource = readJarText(jarFile, "oriShaders/boxBlur/fragment.glsl")
        assertTrue(gaussianSource.contains("Amethyst Ori fast blur"))
        assertTrue(boxSource.contains("Amethyst Ori fast blur"))
        assertFalse(boxSource.contains("for (float i=0.0"))

        val secondPatch = OriImportCompatPatcher.patchInPlace(jarFile)
        assertEquals(0, secondPatch.patchedShaderEntries)
        assertEquals(0, secondPatch.patchedGaussianBlurShaderEntries)
        assertEquals(0, secondPatch.patchedBoxBlurShaderEntries)
        assertFalse(secondPatch.hasAnyPatch)
    }

    @Test
    fun patchInPlace_returnsZeroesWhenTargetShadersAreMissing() {
        val tempDir = Files.createTempDirectory("ori-import-patcher-empty")
        val jarFile = tempDir.resolve("OtherMod.jar").toFile()
        createJar(jarFile, mapOf("example/Placeholder.txt" to "placeholder\n"))

        val patchResult = OriImportCompatPatcher.patchInPlace(jarFile)
        assertEquals(0, patchResult.patchedShaderEntries)
        assertEquals(0, patchResult.patchedGaussianBlurShaderEntries)
        assertEquals(0, patchResult.patchedBoxBlurShaderEntries)
        assertFalse(patchResult.hasAnyPatch)
    }

    private fun createJar(jarFile: java.io.File, entries: Map<String, String>) {
        ZipOutputStream(jarFile.outputStream()).use { zipOut ->
            entries.forEach { (entryName, content) ->
                zipOut.putNextEntry(ZipEntry(entryName))
                zipOut.write(content.toByteArray(StandardCharsets.UTF_8))
                zipOut.closeEntry()
            }
        }
    }

    private fun readJarText(jarFile: java.io.File, entryName: String): String {
        val bytes = JarFileIoUtils.readJarEntryBytes(jarFile, entryName)
        assertNotNull(bytes)
        return String(bytes!!, StandardCharsets.UTF_8)
    }

    private companion object {
        private val legacyGaussianBlurFragment = """
varying vec2 v_texCoords;
uniform sampler2D u_texture;
uniform float resolution;
uniform float radius;
uniform vec2 dir;

void main() {
    vec2 tc = v_texCoords;
    float blur = radius / resolution;
    gl_FragColor = texture2D(u_texture, vec2(tc.x + blur * dir.x, tc.y + blur * dir.y));
}
""".trimIndent()

        private val legacyBoxBlurFragment = """
varying vec2 v_texCoords;
uniform sampler2D u_texture;
uniform float blurSize;
uniform float prec;
uniform vec2 resolution;

void main() {
    vec4 sum = vec4(0.0);
    for (float i=0.0;i<blurSize;i+=prec) {
        for (float j=0.0;j<blurSize;j+=prec) {
            sum += texture2D(u_texture, v_texCoords);
        }
    }
    gl_FragColor = sum;
}
""".trimIndent()
    }
}
