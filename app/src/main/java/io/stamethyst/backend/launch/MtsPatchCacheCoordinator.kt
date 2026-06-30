package io.stamethyst.backend.launch

import android.content.Context
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object MtsPatchCacheCoordinator {
    private const val MIN_CACHE_JAR_BYTES = 1024L * 1024L
    private const val PROPERTY_ENABLED = "amethyst.mts.patch_cache.enabled"
    private const val PROPERTY_CURRENT = "amethyst.mts.patch_cache.current"
    private const val PROPERTY_JAR = "amethyst.mts.patch_cache.jar"
    private const val PROPERTY_MARKER = "amethyst.mts.patch_cache.marker"
    private const val PROPERTY_PACKAGE_DIR = "amethyst.mts.patch_cache.package_dir"
    private const val PROPERTY_EXPECTED = "amethyst.mts.patch_cache.expected"

    @JvmStatic
    fun expectedMarker(context: Context): String = buildCacheMarkerValue(
        desktopJar = RuntimePaths.importedStsJar(context),
        mtsJar = RuntimePaths.importedMtsJar(context),
        baseModJar = RuntimePaths.importedBaseModJar(context),
        stsLibJar = RuntimePaths.importedStsLibJar(context),
        bootBridgeJar = RuntimePaths.bootBridgeJar(context),
        gdxPatchJar = RuntimePaths.gdxPatchJar(context),
        modFileList = RuntimePaths.mtsModFileList(context)
    )

    @JvmStatic
    fun isCacheCurrent(context: Context): Boolean {
        return isCacheCurrent(
            markerFile = RuntimePaths.mtsPatchCacheMarker(context),
            cachedJar = RuntimePaths.mtsPatchCacheJar(context),
            packageDir = RuntimePaths.mtsPatchCachePackageDir(context),
            expectedMarker = expectedMarker(context)
        )
    }

    @JvmStatic
    fun invalidate(context: Context) {
        RuntimePaths.mtsPatchCacheMarker(context).delete()
    }

    @JvmStatic
    fun clear(context: Context) {
        val cachedJar = RuntimePaths.mtsPatchCacheJar(context)
        val markerFile = RuntimePaths.mtsPatchCacheMarker(context)
        val packageDir = RuntimePaths.mtsPatchCachePackageDir(context)
        runCatching { markerFile.delete() }
        runCatching { cachedJar.delete() }
        runCatching {
            packageDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".jar", ignoreCase = true)) {
                    file.delete()
                }
            }
        }
        runCatching {
            File(cachedJar.parentFile ?: File("."), "mts_patch_cache_debug.log").delete()
        }
    }

    @Throws(IOException::class)
    fun appendRuntimeProperties(context: Context, args: MutableList<String>, enabled: Boolean) {
        val expectedMarker = if (enabled) expectedMarker(context) else ""
        appendRuntimeProperties(
            args = args,
            enabled = enabled,
            cacheCurrent = enabled && isCacheCurrent(
                markerFile = RuntimePaths.mtsPatchCacheMarker(context),
                cachedJar = RuntimePaths.mtsPatchCacheJar(context),
                packageDir = RuntimePaths.mtsPatchCachePackageDir(context),
                expectedMarker = expectedMarker
            ),
            cachedJar = RuntimePaths.mtsPatchCacheJar(context),
            markerFile = RuntimePaths.mtsPatchCacheMarker(context),
            packageDir = RuntimePaths.mtsPatchCachePackageDir(context),
            expectedMarker = expectedMarker
        )
    }

    internal fun appendRuntimeProperties(
        args: MutableList<String>,
        enabled: Boolean,
        cacheCurrent: Boolean,
        cachedJar: File,
        markerFile: File,
        packageDir: File,
        expectedMarker: String
    ) {
        args.add("-D$PROPERTY_ENABLED=$enabled")
        args.add("-D$PROPERTY_CURRENT=$cacheCurrent")
        args.add("-D$PROPERTY_JAR=${cachedJar.absolutePath}")
        args.add("-D$PROPERTY_MARKER=${markerFile.absolutePath}")
        args.add("-D$PROPERTY_PACKAGE_DIR=${packageDir.absolutePath}")
        args.add("-D$PROPERTY_EXPECTED=$expectedMarker")
    }

    internal fun isCacheCurrent(
        markerFile: File,
        cachedJar: File,
        packageDir: File,
        expectedMarker: String
    ): Boolean {
        if (expectedMarker.isEmpty() || !cachedJar.isFile || cachedJar.length() < MIN_CACHE_JAR_BYTES) {
            return false
        }
        if (!hasPackageJars(packageDir)) {
            return false
        }
        val actualMarker = try {
            markerFile.takeIf(File::isFile)
                ?.readText(StandardCharsets.UTF_8)
                ?.trim()
                .orEmpty()
        } catch (_: Throwable) {
            ""
        }
        return actualMarker == expectedMarker
    }

    private fun hasPackageJars(packageDir: File): Boolean {
        val files = packageDir.listFiles() ?: return false
        return files.any { file ->
            file.isFile && file.name.endsWith(".jar", ignoreCase = true) && file.length() > 0L
        }
    }

    internal fun buildCacheMarkerValue(
        desktopJar: File,
        mtsJar: File,
        baseModJar: File,
        stsLibJar: File,
        bootBridgeJar: File,
        gdxPatchJar: File,
        modFileList: File
    ): String {
        val rawMarker = buildString {
            append("schema|3").append('\n')
            append(fileFingerprint("desktop", desktopJar)).append('\n')
            append(fileFingerprint("modthespire", mtsJar)).append('\n')
            append(fileFingerprint("basemod", baseModJar)).append('\n')
            append(fileFingerprint("stslib", stsLibJar)).append('\n')
            append(fileFingerprint("bootbridge", bootBridgeJar)).append('\n')
            append(fileFingerprint("gdxpatch", gdxPatchJar)).append('\n')
            append(textFileFingerprint("mod_file_list", modFileList)).append('\n')
            readModFiles(modFileList).forEachIndexed { index, modFile ->
                append(fileFingerprint("mod[$index]", modFile)).append('\n')
            }
        }.trimEnd()
        return sha256(rawMarker)
    }

    private fun readModFiles(modFileList: File): List<File> {
        if (!modFileList.isFile) {
            return emptyList()
        }
        return try {
            modFileList.readLines(StandardCharsets.UTF_8)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map(::File)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun fileFingerprint(label: String, file: File): String {
        val exists = file.isFile
        val length = if (exists) file.length() else -1L
        val lastModified = if (exists) file.lastModified() else -1L
        return "$label|${file.absolutePath}|$length|$lastModified"
    }

    private fun textFileFingerprint(label: String, file: File): String {
        val exists = file.isFile
        val length = if (exists) file.length() else -1L
        val contentHash = if (exists) {
            try {
                sha256(file.readText(StandardCharsets.UTF_8))
            } catch (_: Throwable) {
                "unreadable"
            }
        } else {
            "missing"
        }
        return "$label|${file.absolutePath}|$length|$contentHash"
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
