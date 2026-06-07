package io.stamethyst.backend.resources

import android.content.Context
import io.stamethyst.BuildConfig
import io.stamethyst.R
import io.stamethyst.backend.fs.FileTreeCleaner
import io.stamethyst.backend.github.GithubAcceleratedHttp
import io.stamethyst.backend.github.GithubRequestClients
import io.stamethyst.backend.launch.StartupProgressCallback
import io.stamethyst.backend.launch.progressText
import io.stamethyst.backend.network.NetworkAccelerationPolicy
import io.stamethyst.backend.update.GithubMirrorFallbackException
import io.stamethyst.backend.update.GithubMirrorFallbackFailure
import io.stamethyst.backend.update.UpdateMirrorManager
import io.stamethyst.backend.update.UpdateSource
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import okhttp3.OkHttpClient
import okhttp3.Request

object ExternalResourcePackService {
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val PROBE_CONNECT_TIMEOUT_MS = 4_000
    private const val PROBE_READ_TIMEOUT_MS = 6_000
    private const val USER_AGENT = "SlayTheAmethyst-ResourcePack"
    private const val DOWNLOAD_PROGRESS_REPORT_STEP_BYTES = 256L * 1024L

    private val externalizedAssetRootPaths = listOf(
        "components/jre",
        "components/lwjgl3",
        "components/log4j_runtime",
        "components/mods/ModTheSpire.jar",
        "components/mods/BaseMod.jar",
        "components/mods/StSLib.jar",
        "ui"
    )

    private val requiredCommonAssetFiles = listOf(
        "components/jre/version",
        "components/jre/universal.tar.xz",
        "components/lwjgl3/version",
        "components/lwjgl3/lwjgl-glfw-classes.jar",
        "components/log4j_runtime/log4j-api.jar",
        "components/log4j_runtime/log4j-core.jar",
        "components/mods/ModTheSpire.jar",
        "components/mods/BaseMod.jar",
        "components/mods/StSLib.jar",
        "ui/boot_bright.png",
        "ui/boot_dark.png",
        "ui/update_notice.png"
    )

    private val requiredRuntimeArchiveAlternatives = listOf(
        "components/jre/bin-aarch64.tar.xz",
        "components/jre/bin-arm64.tar.xz"
    )

    val externalizedNativeLibraries: Set<String> = linkedSetOf(
        "libEGL_mesa.so",
        "libOSMesa.so",
        "libVkLayer_khronos_timeline_semaphore.so",
        "libcutils.so",
        "libgdx-freetype.so",
        "libgdx.so",
        "libgl4es_114.so",
        "libglapi.so",
        "libglxshim.so",
        "libjnidispatch.so",
        "liblinkerhook.so",
        "libmobileglues.so",
        "libspirv-cross-c-shared.so",
        "libvulkan_freedreno.so",
        "libzink_dri.so"
    )

    internal data class ResourcePackLinkProbeResult(
        val source: UpdateSource,
        val requestUrl: String,
        val reachable: Boolean,
        val elapsedNanos: Long,
        val candidateIndex: Int,
        val error: Throwable?
    )

    internal data class ResourcePackDownloadCandidate(
        val source: UpdateSource,
        val requestUrl: String,
        val elapsedNanos: Long,
        val candidateIndex: Int
    )

    internal fun orderResourcePackDownloadCandidates(
        probeResults: List<ResourcePackLinkProbeResult>
    ): List<ResourcePackDownloadCandidate> {
        return probeResults
            .asSequence()
            .filter(ResourcePackLinkProbeResult::reachable)
            .sortedWith(
                compareBy<ResourcePackLinkProbeResult> { it.elapsedNanos }
                    .thenBy { it.candidateIndex }
            )
            .map { result ->
                ResourcePackDownloadCandidate(
                    source = result.source,
                    requestUrl = result.requestUrl,
                    elapsedNanos = result.elapsedNanos,
                    candidateIndex = result.candidateIndex
                )
            }
            .toList()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun ensureAvailable(context: Context) {
        ensureAvailable(context, null)
    }

    @JvmStatic
    fun isAvailable(context: Context): Boolean {
        return runCatching {
            collectExternalPackIssues(
                context = context,
                packRoot = RuntimePaths.externalResourcesCurrentDir(context)
            ).isEmpty()
        }.getOrDefault(false)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun ensureAvailable(context: Context, progressCallback: StartupProgressCallback?) {
        throwIfInterrupted()
        RuntimePaths.ensureBaseDirs(context)
        reportProgress(
            progressCallback,
            4,
            context.progressText(R.string.startup_progress_checking_external_resources)
        )

        val externalPackIssues = collectExternalPackIssues(
            context = context,
            packRoot = RuntimePaths.externalResourcesCurrentDir(context)
        )
        if (externalPackIssues.isEmpty()) {
            reportProgress(
                progressCallback,
                100,
                context.progressText(R.string.startup_progress_external_resources_available)
            )
            return
        }

        val bundledMissing = collectMissingBundledResources(context)
        if (bundledMissing.isEmpty()) {
            installBundledResources(
                context = context,
                progressCallback = progressCallback
            )
            reportProgress(
                progressCallback,
                100,
                context.progressText(R.string.startup_progress_external_resources_ready)
            )
            return
        }

        val resourcePackUrl = BuildConfig.RESOURCE_PACK_DOWNLOAD_URL.trim()
        if (resourcePackUrl.isEmpty()) {
            throw IOException(
                "External resource pack is required but RESOURCE_PACK_DOWNLOAD_URL is not configured. " +
                    "Missing bundled resources: ${bundledMissing.joinToString(", ")}. " +
                    "External pack issues: ${externalPackIssues.joinToString(", ")}"
            )
        }

        val stagingRoot = File(
            RuntimePaths.externalResourcesRoot(context),
            "staging-${System.nanoTime()}"
        )
        val downloadFile = File(stagingRoot, "resources.zip")
        val extractedDir = File(stagingRoot, "current")
        prepareCleanDirectory(stagingRoot)
        try {
            downloadResourcePack(
                context = context,
                resourcePackUrl = resourcePackUrl,
                targetFile = downloadFile,
                progressCallback = progressCallback
            )
            throwIfInterrupted()
            reportProgress(
                progressCallback,
                72,
                context.progressText(R.string.startup_progress_extracting_external_resources, 0)
            )
            extractResourcePack(
                archiveFile = downloadFile,
                targetDir = extractedDir,
                progressCallback = progressCallback,
                context = context
            )
            val missingAfterExtract = collectMissingResourcePackContent(extractedDir)
            if (missingAfterExtract.isNotEmpty()) {
                throw IOException(
                    "Downloaded resource pack is incomplete. Missing: " +
                        missingAfterExtract.joinToString(", ")
                )
            }
            writeInstallMarker(context, extractedDir)
            installExtractedResources(
                context = context,
                extractedDir = extractedDir
            )
        } finally {
            FileTreeCleaner.deleteRecursively(stagingRoot)
        }

        reportProgress(
            progressCallback,
            100,
            context.progressText(R.string.startup_progress_external_resources_ready)
        )
    }

    fun isExternalizedNativeLibrary(libraryName: String): Boolean =
        libraryName in externalizedNativeLibraries

    private fun collectExternalPackIssues(context: Context, packRoot: File): List<String> {
        val missing = ArrayList<String>()
        missing += collectMissingResourcePackContent(packRoot)
        val markerVersion = readInstalledResourcePackVersion(
            File(packRoot, RuntimePaths.externalResourcesMarkerFile(context).name)
        )
        val expectedVersion = BuildConfig.RESOURCE_PACK_VERSION.trim()
        if (markerVersion != expectedVersion) {
            missing += "resource pack version $expectedVersion"
        }
        return missing
    }

    private fun collectMissingBundledResources(context: Context): List<String> {
        val missing = ArrayList<String>()
        requiredCommonAssetFiles.forEach { assetPath ->
            if (!bundledAssetFileExists(context, assetPath)) {
                missing += "assets/$assetPath"
            }
        }
        if (requiredRuntimeArchiveAlternatives.none { assetPath ->
                bundledAssetFileExists(context, assetPath)
            }
        ) {
            missing += "assets/components/jre/{bin-aarch64.tar.xz,bin-arm64.tar.xz}"
        }

        val appNativeDir = File(context.applicationInfo.nativeLibraryDir)
        externalizedNativeLibraries.forEach { libraryName ->
            if (!File(appNativeDir, libraryName).isFile) {
                missing += "lib/arm64-v8a/$libraryName"
            }
        }
        return missing
    }

    private fun collectMissingResourcePackContent(packRoot: File): List<String> {
        val missing = ArrayList<String>()
        requiredCommonAssetFiles.forEach { assetPath ->
            if (!File(File(packRoot, "assets"), assetPath).isFile) {
                missing += "assets/$assetPath"
            }
        }
        if (requiredRuntimeArchiveAlternatives.none { assetPath ->
                File(File(packRoot, "assets"), assetPath).isFile
            }
        ) {
            missing += "assets/components/jre/{bin-aarch64.tar.xz,bin-arm64.tar.xz}"
        }
        externalizedNativeLibraries.forEach { libraryName ->
            if (!File(externalNativeDir(packRoot), libraryName).isFile) {
                missing += "lib/arm64-v8a/$libraryName"
            }
        }
        return missing
    }

    @Throws(IOException::class)
    private fun downloadResourcePack(
        context: Context,
        resourcePackUrl: String,
        targetFile: File,
        progressCallback: StartupProgressCallback?
    ) {
        val downloadClients = GithubAcceleratedHttp.createClientPair(
            context = context,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
            followRedirects = true
        )
        val probeClients = GithubAcceleratedHttp.createClientPair(
            context = context,
            connectTimeoutMs = PROBE_CONNECT_TIMEOUT_MS,
            readTimeoutMs = PROBE_READ_TIMEOUT_MS,
            followRedirects = true
        )
        val preferredSource = UpdateMirrorManager.current(context)
        val bypassAcceleratedLinks = NetworkAccelerationPolicy.shouldBypassAcceleratedLinks(context)
        val candidates = UpdateSource.downloadCandidates(
            preferredUserSource = preferredSource,
            metadataSource = preferredSource,
            bypassAcceleratedLinks = bypassAcceleratedLinks
        )
        val orderedCandidates = probeResourcePackDownloadCandidates(
            clients = probeClients,
            resourcePackUrl = resourcePackUrl,
            candidates = candidates,
            progressCallback = progressCallback,
            context = context
        )
        val failures = ArrayList<GithubMirrorFallbackFailure>()
        orderedCandidates.forEach { candidate ->
            throwIfInterrupted()
            reportProgress(
                progressCallback,
                10,
                context.progressText(
                    R.string.startup_progress_selected_external_resource_link,
                    candidate.source.displayName
                )
            )
            try {
                downloadFile(
                    client = downloadClients.pick(candidate.source.usesGithubAcceleration),
                    requestUrl = candidate.requestUrl,
                    targetFile = targetFile,
                    progressCallback = progressCallback,
                    context = context
                )
                return
            } catch (error: Throwable) {
                failures += GithubMirrorFallbackFailure(candidate.source, error)
            }
        }
        throw GithubMirrorFallbackException(failures)
    }

    private fun probeResourcePackDownloadCandidates(
        clients: GithubRequestClients,
        resourcePackUrl: String,
        candidates: List<UpdateSource>,
        progressCallback: StartupProgressCallback?,
        context: Context
    ): List<ResourcePackDownloadCandidate> {
        val results = ArrayList<ResourcePackLinkProbeResult>()
        candidates.forEachIndexed { index, source ->
            throwIfInterrupted()
            reportProgress(
                progressCallback,
                6 + ((index * 4) / candidates.size.coerceAtLeast(1)),
                context.progressText(
                    R.string.startup_progress_checking_external_resource_links,
                    index + 1,
                    candidates.size,
                    source.displayName
                )
            )
            val requestUrl = source.buildUrl(resourcePackUrl)
            results += probeResourcePackLink(
                client = clients.pick(source.usesGithubAcceleration),
                source = source,
                requestUrl = requestUrl,
                candidateIndex = index
            )
        }
        return orderResourcePackDownloadCandidates(results)
            .ifEmpty {
                throw GithubMirrorFallbackException(
                    results.map { result ->
                        GithubMirrorFallbackFailure(
                            source = result.source,
                            error = result.error ?: IOException("Resource pack link is unreachable.")
                        )
                    }
                )
            }
    }

    private fun probeResourcePackLink(
        client: OkHttpClient,
        source: UpdateSource,
        requestUrl: String,
        candidateIndex: Int,
    ): ResourcePackLinkProbeResult {
        val startedAtNs = System.nanoTime()
        return runCatching {
            if (!isResourcePackLinkReachable(client, requestUrl)) {
                throw IOException("Resource pack link is unreachable.")
            }
            ResourcePackLinkProbeResult(
                source = source,
                requestUrl = requestUrl,
                reachable = true,
                elapsedNanos = System.nanoTime() - startedAtNs,
                candidateIndex = candidateIndex,
                error = null
            )
        }.getOrElse { error ->
            ResourcePackLinkProbeResult(
                source = source,
                requestUrl = requestUrl,
                reachable = false,
                elapsedNanos = System.nanoTime() - startedAtNs,
                candidateIndex = candidateIndex,
                error = error
            )
        }
    }

    private fun isResourcePackLinkReachable(client: OkHttpClient, requestUrl: String): Boolean {
        return requestResourcePackProbe(client, requestUrl, "HEAD") ||
            requestResourcePackRangeProbe(client, requestUrl)
    }

    private fun requestResourcePackProbe(
        client: OkHttpClient,
        requestUrl: String,
        method: String,
    ): Boolean {
        val requestBuilder = Request.Builder()
            .url(requestUrl)
            .header("User-Agent", USER_AGENT)
        val request = if (method.equals("HEAD", ignoreCase = true)) {
            requestBuilder.head().build()
        } else {
            requestBuilder.method(method, null).build()
        }
        return try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun requestResourcePackRangeProbe(
        client: OkHttpClient,
        requestUrl: String,
    ): Boolean {
        val request = Request.Builder()
            .url(requestUrl)
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Range", "bytes=0-0")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful || response.code == 206
            }
        } catch (_: Throwable) {
            false
        }
    }

    @Throws(IOException::class)
    private fun downloadFile(
        client: OkHttpClient,
        requestUrl: String,
        targetFile: File,
        progressCallback: StartupProgressCallback?,
        context: Context
    ) {
        throwIfInterrupted()
        val request = Request.Builder()
            .url(requestUrl)
            .get()
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val parent = targetFile.parentFile
                ?: throw IOException("Resource pack target has no parent: ${targetFile.absolutePath}")
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("Failed to create directory: ${parent.absolutePath}")
            }
            val tempFile = File(parent, "${targetFile.name}.part")
            val totalBytes = response.body.contentLength().takeIf { it > 0L }
            response.body.byteStream().use { input ->
                FileOutputStream(tempFile, false).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloadedBytes = 0L
                    var lastReportBytes = 0L
                    while (true) {
                        throwIfInterrupted()
                        val read = input.read(buffer)
                        if (read < 0) {
                            break
                        }
                        if (read == 0) {
                            continue
                        }
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        val shouldReport = downloadedBytes - lastReportBytes >=
                            DOWNLOAD_PROGRESS_REPORT_STEP_BYTES ||
                            totalBytes?.let { downloadedBytes >= it } == true
                        if (shouldReport) {
                            reportProgress(
                                progressCallback,
                                mapDownloadPercent(downloadedBytes, totalBytes),
                                context.progressText(
                                    R.string.startup_progress_downloading_external_resources,
                                    formatBytes(downloadedBytes),
                                    totalBytes?.let(::formatBytes).orEmpty()
                                )
                            )
                            lastReportBytes = downloadedBytes
                        }
                    }
                }
            }
            if (targetFile.exists() && !targetFile.delete()) {
                tempFile.delete()
                throw IOException("Failed to replace file: ${targetFile.absolutePath}")
            }
            if (!tempFile.renameTo(targetFile)) {
                copyFile(tempFile, targetFile)
                tempFile.delete()
            }
        }
    }

    private fun mapDownloadPercent(downloadedBytes: Long, totalBytes: Long?): Int {
        if (totalBytes == null || totalBytes <= 0L) {
            return 18
        }
        val bounded = downloadedBytes.coerceIn(0L, totalBytes)
        return 10 + ((bounded * 58L) / totalBytes).toInt().coerceIn(0, 58)
    }

    @Throws(IOException::class)
    private fun extractResourcePack(
        archiveFile: File,
        targetDir: File,
        progressCallback: StartupProgressCallback?,
        context: Context
    ) {
        prepareCleanDirectory(targetDir)
        ZipFile(archiveFile).use { zipFile ->
            val entries = zipFile.entries().asSequence()
                .filterNot(ZipEntry::isDirectory)
                .toList()
            val totalEntries = entries.size.coerceAtLeast(1)
            entries.forEachIndexed { index, entry ->
                throwIfInterrupted()
                val targetFile = resolveZipTarget(targetDir, entry)
                val parent = targetFile.parentFile
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw IOException("Failed to create directory: ${parent.absolutePath}")
                }
                zipFile.getInputStream(entry).use { input ->
                    FileOutputStream(targetFile, false).use { output ->
                        input.copyTo(output)
                    }
                }
                if (targetFile.name.endsWith(".so", ignoreCase = true)) {
                    targetFile.setExecutable(true, false)
                }
                val percent = ((index + 1) * 100 / totalEntries).coerceIn(0, 100)
                reportProgress(
                    progressCallback,
                    72 + ((percent * 24) / 100),
                    context.progressText(R.string.startup_progress_extracting_external_resources, percent)
                )
            }
        }
    }

    @Throws(IOException::class)
    private fun installBundledResources(
        context: Context,
        progressCallback: StartupProgressCallback?
    ) {
        val stagingRoot = File(
            RuntimePaths.externalResourcesRoot(context),
            "bundled-staging-${System.nanoTime()}"
        )
        val extractedDir = File(stagingRoot, "current")
        prepareCleanDirectory(stagingRoot)
        try {
            copyBundledResources(
                context = context,
                targetDir = extractedDir,
                progressCallback = progressCallback
            )
            val missingAfterCopy = collectMissingResourcePackContent(extractedDir)
            if (missingAfterCopy.isNotEmpty()) {
                throw IOException(
                    "Bundled resource pack is incomplete. Missing: " +
                        missingAfterCopy.joinToString(", ")
                )
            }
            writeInstallMarker(context, extractedDir)
            installExtractedResources(
                context = context,
                extractedDir = extractedDir
            )
        } finally {
            FileTreeCleaner.deleteRecursively(stagingRoot)
        }
    }

    @Throws(IOException::class)
    private fun copyBundledResources(
        context: Context,
        targetDir: File,
        progressCallback: StartupProgressCallback?
    ) {
        prepareCleanDirectory(targetDir)
        val totalSteps = (externalizedAssetRootPaths.size + externalizedNativeLibraries.size)
            .coerceAtLeast(1)
        var completedSteps = 0

        externalizedAssetRootPaths.forEach { assetRoot ->
            throwIfInterrupted()
            copyBundledAssetTree(
                context = context,
                assetPath = assetRoot,
                targetFile = File(File(targetDir, "assets"), assetRoot)
            )
            completedSteps++
            reportBundledCopyProgress(context, progressCallback, completedSteps, totalSteps)
        }

        val appNativeDir = File(context.applicationInfo.nativeLibraryDir)
        val targetNativeDir = externalNativeDir(targetDir)
        externalizedNativeLibraries.forEach { libraryName ->
            throwIfInterrupted()
            val sourceFile = File(appNativeDir, libraryName)
            if (!sourceFile.isFile) {
                throw IOException("Missing bundled native library: ${sourceFile.absolutePath}")
            }
            val targetFile = File(targetNativeDir, libraryName)
            copyFile(sourceFile, targetFile)
            targetFile.setExecutable(true, false)
            completedSteps++
            reportBundledCopyProgress(context, progressCallback, completedSteps, totalSteps)
        }
    }

    @Throws(IOException::class)
    private fun copyBundledAssetTree(context: Context, assetPath: String, targetFile: File) {
        val children = context.assets.list(assetPath)
            ?.filter(String::isNotEmpty)
            .orEmpty()
        if (children.isEmpty()) {
            copyBundledAssetFile(context, assetPath, targetFile)
            return
        }
        if (!targetFile.exists() && !targetFile.mkdirs()) {
            throw IOException("Failed to create directory: ${targetFile.absolutePath}")
        }
        children.forEach { childName ->
            copyBundledAssetTree(
                context = context,
                assetPath = "$assetPath/$childName",
                targetFile = File(targetFile, childName)
            )
        }
    }

    @Throws(IOException::class)
    private fun copyBundledAssetFile(context: Context, assetPath: String, targetFile: File) {
        val parent = targetFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        context.assets.open(assetPath).use { input ->
            FileOutputStream(targetFile, false).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun reportBundledCopyProgress(
        context: Context,
        progressCallback: StartupProgressCallback?,
        completedSteps: Int,
        totalSteps: Int
    ) {
        val percent = ((completedSteps * 100) / totalSteps).coerceIn(0, 100)
        reportProgress(
            progressCallback,
            8 + ((percent * 88) / 100),
            context.progressText(R.string.startup_progress_extracting_external_resources, percent)
        )
    }

    private fun bundledAssetFileExists(context: Context, assetPath: String): Boolean {
        return try {
            context.assets.open(assetPath).use { }
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun readInstalledResourcePackVersion(markerFile: File): String? {
        if (!markerFile.isFile) {
            return null
        }
        return runCatching {
            markerFile.readLines(StandardCharsets.UTF_8)
                .firstOrNull { line -> line.startsWith("version=") }
                ?.substringAfter("version=")
                ?.trim()
        }.getOrNull()
    }

    @Throws(IOException::class)
    private fun resolveZipTarget(targetDir: File, entry: ZipEntry): File {
        val normalizedName = entry.name
            .replace('\\', '/')
            .trimStart('/')
        if (normalizedName.isEmpty() ||
            normalizedName.startsWith("../") ||
            normalizedName.contains("/../")
        ) {
            throw IOException("Unsafe resource pack entry: ${entry.name}")
        }
        val targetFile = File(targetDir, normalizedName)
        val targetRootPath = targetDir.canonicalFile.toPath()
        val targetPath = targetFile.canonicalFile.toPath()
        if (!targetPath.startsWith(targetRootPath)) {
            throw IOException("Unsafe resource pack entry: ${entry.name}")
        }
        return targetFile
    }

    @Throws(IOException::class)
    private fun writeInstallMarker(context: Context, extractedDir: File) {
        val marker = File(extractedDir, RuntimePaths.externalResourcesMarkerFile(context).name)
        marker.writeText(
            "version=${BuildConfig.RESOURCE_PACK_VERSION}\n" +
                "appVersion=${BuildConfig.VERSION_NAME}\n",
            StandardCharsets.UTF_8
        )
    }

    @Throws(IOException::class)
    private fun installExtractedResources(context: Context, extractedDir: File) {
        val root = RuntimePaths.externalResourcesRoot(context)
        if (!root.exists() && !root.mkdirs()) {
            throw IOException("Failed to create directory: ${root.absolutePath}")
        }
        val currentDir = RuntimePaths.externalResourcesCurrentDir(context)
        val previousDir = File(root, "previous")
        FileTreeCleaner.deleteRecursively(previousDir)
        if (currentDir.exists() && !currentDir.renameTo(previousDir)) {
            FileTreeCleaner.deleteRecursively(currentDir)
        }
        if (!extractedDir.renameTo(currentDir)) {
            copyDirectory(extractedDir, currentDir)
            FileTreeCleaner.deleteRecursively(extractedDir)
        }
        FileTreeCleaner.deleteRecursively(previousDir)
    }

    private fun externalNativeDir(currentDir: File): File =
        File(File(currentDir, "lib"), "arm64-v8a")

    @Throws(IOException::class)
    private fun prepareCleanDirectory(directory: File) {
        val parent = directory.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        FileTreeCleaner.deleteRecursively(directory)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Failed to create directory: ${directory.absolutePath}")
        }
    }

    @Throws(IOException::class)
    private fun copyDirectory(source: File, target: File) {
        if (source.isDirectory) {
            if (!target.exists() && !target.mkdirs()) {
                throw IOException("Failed to create directory: ${target.absolutePath}")
            }
            source.listFiles().orEmpty().forEach { child ->
                copyDirectory(child, File(target, child.name))
            }
            return
        }
        copyFile(source, target)
    }

    @Throws(IOException::class)
    private fun copyFile(source: File, target: File) {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        FileInputStream(source).use { input ->
            FileOutputStream(target, false).use { output ->
                input.copyTo(output)
            }
        }
        target.setLastModified(source.lastModified())
    }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.coerceAtLeast(0L).toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return if (unitIndex == 0) {
            bytes.coerceAtLeast(0L).toString() + " " + units[unitIndex]
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unitIndex])
        }
    }

    @Throws(IOException::class)
    private fun throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw IOException("External resource preparation cancelled")
        }
    }

    private fun reportProgress(callback: StartupProgressCallback?, percent: Int, message: String) {
        callback?.onProgress(percent.coerceIn(0, 100), message)
    }
}
