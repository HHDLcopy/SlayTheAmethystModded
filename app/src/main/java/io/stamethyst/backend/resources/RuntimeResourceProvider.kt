package io.stamethyst.backend.resources

import android.content.Context
import android.content.res.AssetManager
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.LinkedHashSet

class RuntimeResourceProvider(
    context: Context,
    private val assets: AssetManager = context.assets
) {
    private val externalAssetsDir: File = RuntimePaths.externalResourcesAssetsDir(context)

    @Throws(IOException::class)
    fun list(path: String): Array<String> {
        val names = LinkedHashSet<String>()
        val assetNames = try {
            assets.list(path)
        } catch (_: IOException) {
            null
        }
        assetNames
            ?.filter(String::isNotEmpty)
            ?.forEach(names::add)

        val external = externalFile(path)
        if (external.isDirectory) {
            external.list()
                ?.filter(String::isNotEmpty)
                ?.forEach(names::add)
        }
        return names.toTypedArray()
    }

    @Throws(IOException::class)
    fun open(path: String): InputStream {
        try {
            return assets.open(path)
        } catch (assetError: IOException) {
            val external = externalFile(path)
            if (external.isFile) {
                return FileInputStream(external)
            }
            throw assetError
        }
    }

    fun exists(path: String): Boolean {
        if (assetFileExists(path)) {
            return true
        }
        return externalFile(path).isFile
    }

    fun contentVersion(path: String): Long {
        val external = externalFile(path)
        if (external.isFile) {
            return external.lastModified().takeIf { it > 0L }
                ?: external.length().coerceAtLeast(1L)
        }
        return if (assetFileExists(path)) {
            -1L
        } else {
            0L
        }
    }

    fun hasChildren(path: String): Boolean {
        return try {
            list(path).isNotEmpty()
        } catch (_: IOException) {
            false
        }
    }

    private fun assetFileExists(path: String): Boolean {
        return try {
            assets.open(path).use { _: InputStream -> }
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun externalFile(path: String): File {
        val normalizedPath = path
            .replace('\\', '/')
            .trimStart('/')
        return File(externalAssetsDir, normalizedPath)
    }
}
