package io.stamethyst.backend.easytier

import android.content.Context
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

internal object EasyTierNativeLibraryLoader {
    private const val CODE_CACHE_DIR_NAME = "easytier-native"
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
            val sourceLibraryFiles = requireLibraryFiles(
                RuntimePaths.externalNativeLibDir(context)
            )
            val libraryFiles = stageLibraryFilesForLoading(context, sourceLibraryFiles)
            // Android's linker does not reliably permit executable mappings from
            // the writable resource-pack directory. codeCacheDir is the approved
            // private location for dynamically loaded native code.
            libraryFiles.forEach { libraryFile -> System.load(libraryFile.canonicalPath) }
            loaded = true
        }
    }

    internal fun requireLibraryFiles(libraryDir: File): List<File> =
        libraryFileNames.map { fileName ->
            File(libraryDir, fileName).also(::requireLibraryFile)
        }

    @Throws(IOException::class)
    internal fun stageLibraryFilesForLoading(
        context: Context,
        sourceLibraryFiles: List<File>
    ): List<File> {
        val targetDir = File(context.codeCacheDir, CODE_CACHE_DIR_NAME)
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("Failed to create EasyTier native code cache: ${targetDir.absolutePath}")
        }
        return sourceLibraryFiles.map { source ->
            val target = File(targetDir, source.name)
            if (isCachedLibraryCurrent(source, target)) {
                target
            } else {
                copyLibraryAtomically(source, target)
            }
        }
    }

    internal fun isCachedLibraryCurrent(source: File, target: File): Boolean =
        target.isFile &&
            target.length() == source.length() &&
            target.lastModified() == source.lastModified()

    @Throws(IOException::class)
    private fun copyLibraryAtomically(source: File, target: File): File {
        val temporary = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(temporary, false).use { output -> input.copyTo(output) }
            }
            if (!temporary.setExecutable(true, false)) {
                throw IOException("Failed to mark EasyTier native library executable: ${temporary.absolutePath}")
            }
            if (source.lastModified() > 0L) {
                temporary.setLastModified(source.lastModified())
            }
            if (target.exists() && !target.delete()) {
                throw IOException("Failed to replace EasyTier native library: ${target.absolutePath}")
            }
            if (!temporary.renameTo(target)) {
                throw IOException("Failed to install EasyTier native library: ${target.absolutePath}")
            }
            return target
        } finally {
            if (temporary.exists()) {
                temporary.delete()
            }
        }
    }

    private fun requireLibraryFile(file: File) {
        if (!file.isFile || file.length() <= 0L) {
            throw UnsatisfiedLinkError(
                "EasyTier native runtime is missing from the installed resource pack: ${file.absolutePath}"
            )
        }
    }
}
