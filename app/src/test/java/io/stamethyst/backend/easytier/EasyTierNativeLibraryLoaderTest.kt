package io.stamethyst.backend.easytier

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EasyTierNativeLibraryLoaderTest {
    @Test
    fun requireLibraryFiles_returnsFfiBeforeJni() {
        val root = Files.createTempDirectory("easytier-libraries-").toFile()
        try {
            File(root, "libeasytier_ffi.so").writeText("ffi", StandardCharsets.UTF_8)
            File(root, "libeasytier_android_jni.so").writeText("jni", StandardCharsets.UTF_8)

            assertEquals(
                listOf("libeasytier_ffi.so", "libeasytier_android_jni.so"),
                EasyTierNativeLibraryLoader.requireLibraryFiles(root).map { it.name }
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun requireLibraryFiles_rejectsMissingOrEmptyLibrary() {
        val root = Files.createTempDirectory("easytier-libraries-missing-").toFile()
        try {
            File(root, "libeasytier_ffi.so").createNewFile()

            val error = runCatching {
                EasyTierNativeLibraryLoader.requireLibraryFiles(root)
            }.exceptionOrNull()

            assertTrue(error is UnsatisfiedLinkError)
            assertTrue(error?.message.orEmpty().contains("libeasytier_ffi.so"))
        } finally {
            root.deleteRecursively()
        }
    }
}
