package io.stamethyst.backend.easytier

import android.content.Context
import io.stamethyst.config.RuntimePaths
import java.io.File

internal object EasyTierNativeLibraryLoader {
    private val libraryFileNames = listOf(
        "libeasytier_ffi.so",
        "libeasytier_android_jni.so"
    )
    private val loadLock = Any()

    @Volatile
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) {
            return
        }
        synchronized(loadLock) {
            if (loaded) {
                return
            }
            val libraryFiles = requireLibraryFiles(
                RuntimePaths.externalNativeLibDir(context)
            )
            // The resource pack is outside the APK's native-library directory.
            // Loading by absolute path is therefore required instead of loadLibrary.
            libraryFiles.forEach { libraryFile -> System.load(libraryFile.canonicalPath) }
            loaded = true
        }
    }

    internal fun requireLibraryFiles(libraryDir: File): List<File> =
        libraryFileNames.map { fileName ->
            File(libraryDir, fileName).also(::requireLibraryFile)
        }

    private fun requireLibraryFile(file: File) {
        if (!file.isFile || file.length() <= 0L) {
            throw UnsatisfiedLinkError(
                "EasyTier native runtime is missing from the installed resource pack: ${file.absolutePath}"
            )
        }
    }
}
