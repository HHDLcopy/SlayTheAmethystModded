package io.stamethyst.backend.launch

import android.content.Context
import io.stamethyst.backend.fs.FileTreeCleaner
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipFile

internal object MtsPatchCacheCoordinator {
    private const val MIN_CACHE_JAR_BYTES = 1024L * 1024L
    private val SEPARATOR_BYTE = byteArrayOf('|'.code.toByte())
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
            append("schema|7").append('\n')
            append(jarFingerprint("desktop", desktopJar)).append('\n')
            append(jarFingerprint("modthespire", mtsJar)).append('\n')
            append(jarFingerprint("basemod", baseModJar)).append('\n')
            append(jarFingerprint("stslib", stsLibJar)).append('\n')
            append(jarFingerprint("bootbridge", bootBridgeJar)).append('\n')
            append(jarFingerprint("gdxpatch", gdxPatchJar)).append('\n')
            append(textFileFingerprint("mod_file_list", modFileList)).append('\n')
            readModFiles(modFileList).forEachIndexed { index, modFile ->
                append(jarFingerprint("mod[$index]", modFile)).append('\n')
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

    /**
     * Fingerprints a jar by its central directory rather than by mtime.
     *
     * Size and mtime alone let a jar that was rebuilt in place — same size, mtime
     * preserved or reset by a copy — pass as unchanged, which yields a stale cache hit
     * against mod bytecode that no longer exists. Hashing the whole file would be
     * correct but has to read every byte of every mod on each launch, which cancels out
     * the cache hit it is protecting.
     *
     * The central directory is the middle ground: it already stores a per-entry CRC32
     * that the writer computed over the entry's uncompressed bytes, so any content
     * change moves it. Reading it costs a few KB of seeks instead of the whole archive.
     *
     * Falls back to size and mtime when the file is not a readable zip, so non-jar or
     * corrupt entries still contribute something rather than silently collapsing to a
     * constant.
     */
    private fun jarFingerprint(label: String, file: File): String {
        if (!file.isFile) {
            return "$label|${file.absolutePath}|-1|-1"
        }
        val entryDigest = try {
            ZipFile(file).use { zip ->
                val digest = MessageDigest.getInstance("SHA-256")
                // Enumeration order follows the central directory, which is stable for a
                // given archive, so no extra sort is needed to keep this deterministic.
                for (entry in zip.entries()) {
                    digest.update(entry.name.toByteArray(StandardCharsets.UTF_8))
                    digest.update(SEPARATOR_BYTE)
                    digest.update(entry.size.toString().toByteArray(StandardCharsets.UTF_8))
                    digest.update(SEPARATOR_BYTE)
                    digest.update(entry.crc.toString().toByteArray(StandardCharsets.UTF_8))
                    digest.update(SEPARATOR_BYTE)
                }
                digest.digest().toHex()
            }
        } catch (_: Throwable) {
            null
        }
        if (entryDigest == null) {
            return "$label|${file.absolutePath}|${file.length()}|${file.lastModified()}|nozip"
        }
        return "$label|${file.absolutePath}|${file.length()}|$entryDigest"
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

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(StandardCharsets.UTF_8))
            .toHex()

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
}
