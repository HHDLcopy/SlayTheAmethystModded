package io.stamethyst.backend.easytier

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files

internal class EasyTierTestRoots private constructor(
    val rootDir: File,
    val context: Context,
) {
    companion object {
        fun create(prefix: String): EasyTierTestRoots {
            val rootDir = Files.createTempDirectory(prefix).toFile()
            val filesDir = File(rootDir, "internal-files").apply { mkdirs() }
            val externalFilesDir = File(rootDir, "external-files").apply { mkdirs() }
            return EasyTierTestRoots(
                rootDir = rootDir,
                context = object : ContextWrapper(Application()) {
                    override fun getFilesDir(): File = filesDir

                    override fun getExternalFilesDir(type: String?): File = externalFilesDir

                    override fun getPackageName(): String = "io.stamethyst.test"
                }
            )
        }
    }
}
