package io.stamethyst.backend.launch

import android.content.Context
import io.stamethyst.backend.fs.FileTreeCleaner
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
    private const val PROPERTY_BASE_JAR = "amethyst.mts.patch_cache.base_jar"
    private const val PROPERTY_MARKER = "amethyst.mts.patch_cache.marker"
    private const val PROPERTY_PACKAGE_DIR = "amethyst.mts.patch_cache.package_dir"
    private const val PROPERTY_EXPECTED = "amethyst.mts.patch_cache.expected"
    private const val PROPERTY_GAME_DIR = "amethyst.mts.patch_cache.game_dir"
    private const val PROPERTY_LOADOUT_SCAN_CACHE_ENABLED = "amethyst.runtime_compat.loadout_class_scan_cache"
    private const val PROPERTY_LOADOUT_SCAN_CACHE_DIR = "amethyst.loadout.scan_cache_dir"

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
        deleteCacheFiles(RuntimePaths.knownMtsPatchCacheArtifacts(context))
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
            baseJar = RuntimePaths.importedStsJar(context),
            markerFile = RuntimePaths.mtsPatchCacheMarker(context),
            packageDir = RuntimePaths.mtsPatchCachePackageDir(context),
            expectedMarker = expectedMarker,
            gameDir = RuntimePaths.stsRoot(context),
            loadoutScanCacheDir = RuntimePaths.mtsPatchCacheLoadoutScanCacheDir(context)
        )
    }

    internal fun appendRuntimeProperties(
        args: MutableList<String>,
        enabled: Boolean,
        cacheCurrent: Boolean,
        cachedJar: File,
        baseJar: File,
        markerFile: File,
        packageDir: File,
        expectedMarker: String,
        gameDir: File,
        loadoutScanCacheDir: File
    ) {
        args.add("-D$PROPERTY_ENABLED=$enabled")
        args.add("-D$PROPERTY_CURRENT=$cacheCurrent")
        args.add("-D$PROPERTY_JAR=${cachedJar.absolutePath}")
        args.add("-D$PROPERTY_BASE_JAR=${baseJar.absolutePath}")
        args.add("-D$PROPERTY_MARKER=${markerFile.absolutePath}")
        args.add("-D$PROPERTY_PACKAGE_DIR=${packageDir.absolutePath}")
        args.add("-D$PROPERTY_EXPECTED=$expectedMarker")
        args.add("-D$PROPERTY_GAME_DIR=${gameDir.absolutePath}")
        args.add("-D$PROPERTY_LOADOUT_SCAN_CACHE_ENABLED=$enabled")
        args.add("-D$PROPERTY_LOADOUT_SCAN_CACHE_DIR=${loadoutScanCacheDir.absolutePath}")
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

    private fun deleteCacheFiles(files: List<File>) {
        files.forEach { file ->
            runCatching {
                if (file.isDirectory) {
                    FileTreeCleaner.deleteRecursively(file)
                } else {
                    file.delete()
                }
            }
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
            append("schema|6").append('\n')
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
