package io.stamethyst.ui.main

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.backend.workshop.WorkshopDownloadTaskRecord
import io.stamethyst.backend.workshop.WorkshopDownloadTaskStatus
import io.stamethyst.backend.workshop.WorkshopDownloadTaskStore
import io.stamethyst.backend.workshop.WorkshopInstalledModRecord
import io.stamethyst.backend.workshop.WorkshopItemDetails
import io.stamethyst.backend.workshop.WorkshopItemSummary
import io.stamethyst.backend.workshop.WorkshopMetadataStore
import io.stamethyst.backend.workshop.WorkshopModCardState
import io.stamethyst.config.RuntimePaths
import io.stamethyst.navigation.Route
import io.stamethyst.ui.LauncherContent
import io.stamethyst.ui.settings.core.SettingsScreenViewModel
import io.stamethyst.ui.theme.LauncherTheme
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.delay

class ModPageTransitionBenchmarkActivity : AppCompatActivity() {
    private val mainViewModel: MainScreenViewModel by viewModels()
    private val settingsViewModel: SettingsScreenViewModel by viewModels()
    private var fullMountMarkerReady by mutableStateOf(false)
    private var transitionStartReady by mutableStateOf(false)
    private var currentDockRoute by mutableStateOf<Route?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val modCount = intent.getIntExtra(EXTRA_MOD_COUNT, DEFAULT_BENCHMARK_MOD_COUNT)
            .coerceAtLeast(1)
        val includeWorkshopState = intent.getBooleanExtra(EXTRA_WORKSHOP_MODS, false)
        val completedDownloadTaskCount = intent.getIntExtra(EXTRA_COMPLETED_DOWNLOAD_TASK_COUNT, 0)
            .coerceAtLeast(0)
        val downloadLogBytes = intent.getIntExtra(EXTRA_DOWNLOAD_TASK_LOG_BYTES, 0)
            .coerceAtLeast(0)
        val initialRoute = intent.getStringExtra(EXTRA_INITIAL_PAGE)
            ?.toBenchmarkRoute()
            ?: Route.Main
        val waitForFullMountMarker = intent.getBooleanExtra(EXTRA_FULL_MOUNT_MARKER, false)

        Trace.beginSection(TRACE_SECTION_PREPARE_FIXTURE)
        val prepareStartMs = SystemClock.elapsedRealtime()
        val fixture = try {
            BenchmarkWorkshopFixture.prepare(
                host = this,
                modCount = modCount,
                includeWorkshopState = includeWorkshopState,
                completedDownloadTaskCount = completedDownloadTaskCount,
                downloadLogBytes = downloadLogBytes,
            )
        } finally {
            Trace.endSection()
        }
        Log.i(
            TAG,
            "prepared fixture modCount=$modCount includeWorkshop=$includeWorkshopState " +
                "completedDownloadTasks=$completedDownloadTaskCount downloadLogBytes=$downloadLogBytes " +
                "rewritten=${fixture.rewritten} durationMs=${SystemClock.elapsedRealtime() - prepareStartMs}"
        )

        settingsViewModel.syncThemeAppearance(this)
        setContent {
            LauncherTheme(
                themeMode = settingsViewModel.uiState.themeMode,
                themeColor = settingsViewModel.uiState.themeColor,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { testTagsAsResourceId = true }
                ) {
                    LauncherContent(
                        initialRoute = initialRoute,
                        mainViewModel = mainViewModel,
                        settingsViewModel = settingsViewModel,
                        onCurrentDockRouteChanged = { route ->
                            currentDockRoute = route
                        },
                    )
                    if (fullMountMarkerReady) {
                        Box(
                            modifier = Modifier
                                .size(1.dp)
                                .testTag(MODS_TRANSITION_FULL_MOUNT_READY_TAG)
                        )
                    }
                    if (transitionStartReady) {
                        Box(
                            modifier = Modifier
                                .size(1.dp)
                                .testTag(WORKSHOP_TRANSITION_START_READY_TAG)
                        )
                    }
                }
                LaunchedEffect(
                    waitForFullMountMarker,
                    mainViewModel.uiState.initializing,
                    mainViewModel.uiState.optionalMods.size,
                    currentDockRoute,
                    initialRoute,
                ) {
                    if (!waitForFullMountMarker) {
                        transitionStartReady = false
                        return@LaunchedEffect
                    }
                    if (initialRoute == Route.Workshop &&
                        currentDockRoute == Route.Workshop &&
                        !mainViewModel.uiState.initializing &&
                        mainViewModel.uiState.optionalMods.size >= modCount
                    ) {
                        delay(TRANSITION_START_READY_DELAY_MS)
                        transitionStartReady = true
                        Log.i(
                            TAG,
                            "transition start ready optionalMods=${mainViewModel.uiState.optionalMods.size}"
                        )
                    } else {
                        transitionStartReady = false
                    }
                }
                LaunchedEffect(
                    waitForFullMountMarker,
                    mainViewModel.uiState.initializing,
                    mainViewModel.uiState.optionalMods.size,
                    currentDockRoute,
                ) {
                    if (!waitForFullMountMarker) {
                        fullMountMarkerReady = false
                        return@LaunchedEffect
                    }
                    if (currentDockRoute == Route.Mods &&
                        !mainViewModel.uiState.initializing &&
                        mainViewModel.uiState.optionalMods.size >= modCount
                    ) {
                        delay(FULL_MOUNT_MARKER_DELAY_MS)
                        fullMountMarkerReady = true
                        Log.i(
                            TAG,
                            "full mount marker ready optionalMods=${mainViewModel.uiState.optionalMods.size}"
                        )
                    } else {
                        fullMountMarkerReady = false
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_MOD_COUNT = "io.stamethyst.benchmark.extra.MOD_COUNT"
        const val EXTRA_WORKSHOP_MODS = "io.stamethyst.benchmark.extra.WORKSHOP_MODS"
        const val EXTRA_COMPLETED_DOWNLOAD_TASK_COUNT = "io.stamethyst.benchmark.extra.COMPLETED_DOWNLOAD_TASK_COUNT"
        const val EXTRA_DOWNLOAD_TASK_LOG_BYTES = "io.stamethyst.benchmark.extra.DOWNLOAD_TASK_LOG_BYTES"
        const val EXTRA_INITIAL_PAGE = "io.stamethyst.benchmark.extra.INITIAL_PAGE"
        const val EXTRA_FULL_MOUNT_MARKER = "io.stamethyst.benchmark.extra.FULL_MOUNT_MARKER"
        const val MODS_TRANSITION_FULL_MOUNT_READY_TAG = "mods_transition_full_mount_ready"
        const val WORKSHOP_TRANSITION_START_READY_TAG = "workshop_transition_start_ready"

        private const val TAG = "ModPageBenchmarkActivity"
        private const val TRACE_SECTION_PREPARE_FIXTURE = "prepareModPageBenchmarkFixture"
        private const val DEFAULT_BENCHMARK_MOD_COUNT = 400
        private const val TRANSITION_START_READY_DELAY_MS = 240L
        private const val FULL_MOUNT_MARKER_DELAY_MS = 360L
        private const val PAGE_MAIN = "Main"
        private const val PAGE_MODS = "Mods"
        private const val PAGE_WORKSHOP = "Workshop"
        private const val PAGE_SETTINGS = "Settings"
    }
}

private data class BenchmarkFixtureResult(
    val rewritten: Boolean,
)

private object BenchmarkWorkshopFixture {
    private const val TAG = "ModPageBenchmarkFixture"
    private const val FIXTURE_VERSION = 4
    private const val APP_ID = 646570u
    private const val BENCHMARK_MOD_ID_PREFIX = "benchmark_workshop_mod_"
    private const val BENCHMARK_FILE_PREFIX = "benchmark_workshop_mod_"
    private const val BENCHMARK_PREVIEW_FILE_NAME = "preview.jpg"
    private const val BENCHMARK_PREVIEW_CACHE_DIR = "workshop-preview-cache"
    private const val BENCHMARK_PUBLISHED_FILE_ID_START = 3_900_000_000UL
    private const val SIGNATURE_FILE_NAME = ".mod_page_benchmark_fixture"
    private const val PREVIEW_WIDTH = 512
    private const val PREVIEW_HEIGHT = 288
    private const val PREVIEW_QUALITY = 86

    fun prepare(
        host: AppCompatActivity,
        modCount: Int,
        includeWorkshopState: Boolean,
        completedDownloadTaskCount: Int,
        downloadLogBytes: Int,
    ): BenchmarkFixtureResult {
        val libraryDir = RuntimePaths.optionalModsLibraryDir(host)
        if (!libraryDir.exists() && !libraryDir.mkdirs()) {
            throw IllegalStateException("Failed to create optional mod library: ${libraryDir.absolutePath}")
        }
        val signatureFile = File(libraryDir, SIGNATURE_FILE_NAME)
        val expectedSignature = buildSignature(
            modCount = modCount,
            includeWorkshopState = includeWorkshopState,
            completedDownloadTaskCount = completedDownloadTaskCount,
            downloadLogBytes = downloadLogBytes,
        )
        val existingSignature = signatureFile.takeIf(File::isFile)
            ?.readText(StandardCharsets.UTF_8)
            ?.trim()
            .orEmpty()
        val store = WorkshopMetadataStore(host)
        val existingRecords = store.list()
        val existingBenchmarkRecords = existingRecords.filter { it.isBenchmarkRecord() }
        val expectedBenchmarkRecordCount = if (includeWorkshopState) modCount else 0
        val benchmarkRecordsAlreadyPresent = existingRecords.count { it.isBenchmarkRecord() }
        val benchmarkJarsAlreadyPresent = countBenchmarkJars(libraryDir)
        val benchmarkPreviewArtifactsAlreadyPresent = countBenchmarkPreviewArtifacts(host, modCount)
        val benchmarkDownloadTasksReady = hasExpectedBenchmarkDownloadTasks(
            host = host,
            completedDownloadTaskCount = completedDownloadTaskCount,
            downloadLogBytes = downloadLogBytes,
        )
        val needsRewrite = existingSignature != expectedSignature ||
            benchmarkRecordsAlreadyPresent != expectedBenchmarkRecordCount ||
            benchmarkJarsAlreadyPresent != modCount ||
            !benchmarkDownloadTasksReady ||
            includeWorkshopState && benchmarkPreviewArtifactsAlreadyPresent != modCount * 2

        if (!needsRewrite) {
            Log.i(TAG, "fixture reuse modCount=$modCount signature=$expectedSignature")
            return BenchmarkFixtureResult(rewritten = false)
        }

        val rewriteStartMs = SystemClock.elapsedRealtime()
        cleanupBenchmarkJars(libraryDir)
        cleanupBenchmarkPreviewArtifacts(host, existingBenchmarkRecords, modCount)
        val records = ArrayList<WorkshopInstalledModRecord>(modCount)
        val enabledModIds = ArrayList<String>(modCount / 2)
        repeat(modCount) { index ->
            val jarFile = File(libraryDir, "${BENCHMARK_FILE_PREFIX}${index.toString().padStart(4, '0')}.jar")
            createBenchmarkJar(jarFile, index, includeWorkshopState)
            val modId = BENCHMARK_MOD_ID_PREFIX + index
            if (index % 3 != 0) {
                enabledModIds.add(modId)
            }
            if (includeWorkshopState) {
                val publishedFileId = BENCHMARK_PUBLISHED_FILE_ID_START + index.toULong()
                val previewPath = createBenchmarkPreviewImages(host, publishedFileId, index)
                records.add(createWorkshopRecord(jarFile, publishedFileId, index, previewPath))
            }
        }

        rewriteBenchmarkDownloadTasks(
            host = host,
            completedDownloadTaskCount = completedDownloadTaskCount,
            downloadLogBytes = downloadLogBytes,
        )
        val preservedRecords = existingRecords.filterNot { it.isBenchmarkRecord() }
        val preservedEnabledModIds = ModManager.listEnabledOptionalModIds(host)
            .filterNot { it.startsWith(BENCHMARK_MOD_ID_PREFIX) }
        store.save(preservedRecords + records)
        ModManager.replaceEnabledOptionalModIds(host, preservedEnabledModIds + enabledModIds)
        signatureFile.writeText(expectedSignature, StandardCharsets.UTF_8)
        Log.i(
            TAG,
            "fixture rewrite modCount=$modCount records=${records.size} " +
                "downloadTasks=$completedDownloadTaskCount " +
                "jars=${countBenchmarkJars(libraryDir)} durationMs=${SystemClock.elapsedRealtime() - rewriteStartMs}"
        )
        return BenchmarkFixtureResult(rewritten = true)
    }

    private fun buildSignature(
        modCount: Int,
        includeWorkshopState: Boolean,
        completedDownloadTaskCount: Int,
        downloadLogBytes: Int,
    ): String {
        return "version=$FIXTURE_VERSION\n" +
            "modCount=$modCount\n" +
            "workshop=$includeWorkshopState\n" +
            "completedDownloadTasks=$completedDownloadTaskCount\n" +
            "downloadLogBytes=$downloadLogBytes"
    }

    private fun createBenchmarkJar(
        target: File,
        index: Int,
        includeWorkshopState: Boolean,
    ) {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IllegalStateException("Failed to create benchmark jar directory: ${parent.absolutePath}")
        }
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("ModTheSpire.json"))
            zip.write(buildManifestJson(index, includeWorkshopState).toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("benchmark/payload-${index.toString().padStart(4, '0')}.txt"))
            zip.write(buildPayload(index).toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }
        target.setLastModified(1_724_000_000_000L + index * 60_000L)
    }

    private fun buildManifestJson(index: Int, includeWorkshopState: Boolean): String {
        val dependencies = when {
            index % 11 == 0 -> """["basemod","stslib","benchmark_dependency_${index % 17}"]"""
            index % 7 == 0 -> """["basemod"]"""
            else -> "[]"
        }
        val displayName = if (includeWorkshopState) {
            "Workshop Benchmark Mod $index"
        } else {
            "Benchmark Mod $index"
        }
        return """
            {
              "modid": "$BENCHMARK_MOD_ID_PREFIX$index",
              "name": "$displayName",
              "author_list": ["Benchmark Fixture"],
              "description": "Benchmark fixture that mirrors a Steam Workshop imported mod card with manifest metadata, dependencies, status badges, size formatting, and a realistic local jar path.",
              "version": "1.${index % 23}.${index % 97}",
              "sts_version": "12-22-2020",
              "mts_version": "3.30.0",
              "dependencies": $dependencies
            }
        """.trimIndent()
    }

    private fun buildPayload(index: Int): String {
        return buildString {
            appendLine("benchmark-mod=$index")
            repeat(80) { line ->
                append("This payload keeps the jar non-empty and stable for metadata scanning. ")
                append("index=")
                append(index)
                append(" line=")
                appendLine(line)
            }
        }
    }

    private fun createWorkshopRecord(
        jarFile: File,
        publishedFileId: ULong,
        index: Int,
        previewPath: String,
    ): WorkshopInstalledModRecord {
        val updatedAt = 1_724_000_000_000L + index * 60_000L
        val dependencySummaries = buildWorkshopDependencies(index)
        return WorkshopInstalledModRecord(
            appId = APP_ID,
            publishedFileId = publishedFileId,
            title = "Workshop Benchmark Mod $index",
            description = "Imported from the workshop benchmark fixture. This record uses the same local jar metadata path as a market download and patched import.",
            previewUrl = "https://steamuserimages-a.akamaihd.net/ugc/benchmark_${index}.jpg",
            versionText = "Updated ${index % 31 + 1}/2024",
            updatedAtMillis = updatedAt,
            installedAtMillis = updatedAt + 15_000L,
            localJarPath = jarFile.absolutePath,
            localJarPaths = listOf(jarFile.absolutePath),
            cardState = WorkshopModCardState.ImportedPatched,
            statusText = "已导入并完成兼容处理",
            localPreviewImagePath = previewPath,
            dependencies = dependencySummaries,
        )
    }

    private fun createBenchmarkPreviewImages(
        host: AppCompatActivity,
        publishedFileId: ULong,
        index: Int,
    ): String {
        val workshopPreviewFile = File(
            host.filesDir,
            "workshop/$APP_ID/$publishedFileId/$BENCHMARK_PREVIEW_FILE_NAME"
        )
        val browseCacheFile = File(
            host.filesDir,
            "$BENCHMARK_PREVIEW_CACHE_DIR/$publishedFileId.jpg"
        )
        val bitmap = createBenchmarkPreviewBitmap(index)
        try {
            writeBitmapJpeg(bitmap, workshopPreviewFile)
            writeBitmapJpeg(bitmap, browseCacheFile)
        } finally {
            bitmap.recycle()
        }
        return BENCHMARK_PREVIEW_FILE_NAME
    }

    private fun createBenchmarkPreviewBitmap(index: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(PREVIEW_WIDTH, PREVIEW_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val hue = ((index * 37) % 360).toFloat()
        val baseColor = Color.HSVToColor(floatArrayOf(hue, 0.46f, 0.78f))
        val darkColor = Color.HSVToColor(floatArrayOf(hue, 0.62f, 0.38f))
        val accentColor = Color.HSVToColor(floatArrayOf((hue + 84f) % 360f, 0.68f, 0.94f))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        canvas.drawColor(baseColor)
        paint.color = darkColor
        canvas.drawRect(0f, 178f, PREVIEW_WIDTH.toFloat(), PREVIEW_HEIGHT.toFloat(), paint)
        paint.color = Color.argb(68, 255, 255, 255)
        repeat(7) { stripe ->
            val left = -80f + stripe * 96f
            canvas.drawRoundRect(RectF(left, 0f, left + 48f, PREVIEW_HEIGHT.toFloat()), 28f, 28f, paint)
        }
        paint.color = accentColor
        canvas.drawRoundRect(RectF(28f, 32f, 196f, 154f), 22f, 22f, paint)
        paint.color = Color.argb(215, 0, 0, 0)
        canvas.drawRoundRect(RectF(214f, 34f, 484f, 92f), 18f, 18f, paint)
        paint.color = Color.argb(170, 255, 255, 255)
        canvas.drawRoundRect(RectF(214f, 112f, 454f, 136f), 12f, 12f, paint)
        canvas.drawRoundRect(RectF(214f, 150f, 420f, 174f), 12f, 12f, paint)
        canvas.drawRoundRect(RectF(28f, 200f, 484f, 248f), 20f, 20f, paint)

        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.color = Color.WHITE
        paint.textSize = 42f
        canvas.drawText("WORKSHOP", 232f, 76f, paint)
        paint.textSize = 54f
        canvas.drawText("#${(index + 1).toString().padStart(3, '0')}", 54f, 110f, paint)
        paint.textSize = 28f
        canvas.drawText("Benchmark Mod", 52f, 234f, paint)
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        paint.textSize = 22f
        canvas.drawText("Imported preview image", 244f, 234f, paint)
        return bitmap
    }

    private fun writeBitmapJpeg(bitmap: Bitmap, outputFile: File) {
        val directory = outputFile.parentFile
            ?: throw IllegalStateException("Preview output has no parent: ${outputFile.absolutePath}")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Failed to create preview directory: ${directory.absolutePath}")
        }
        val tempFile = File(directory, "${outputFile.name}.tmp")
        tempFile.outputStream().buffered().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, PREVIEW_QUALITY, output)) {
                throw IllegalStateException("Failed to encode preview image: ${outputFile.absolutePath}")
            }
        }
        if (outputFile.exists() && !outputFile.delete()) {
            tempFile.delete()
            throw IllegalStateException("Failed to replace preview image: ${outputFile.absolutePath}")
        }
        if (!tempFile.renameTo(outputFile)) {
            tempFile.delete()
            throw IllegalStateException("Failed to move preview image into place: ${outputFile.absolutePath}")
        }
    }

    private fun buildWorkshopDependencies(index: Int): List<WorkshopItemSummary> {
        if (index % 9 != 0) return emptyList()
        return listOf(
            WorkshopItemSummary(
                appId = APP_ID,
                publishedFileId = (BENCHMARK_PUBLISHED_FILE_ID_START + 90_000UL + index.toULong()),
                title = "Benchmark Dependency ${index % 17}",
                description = "Synthetic dependency metadata for benchmark realism.",
                previewUrl = "",
                updatedAtMillis = 1_724_000_000_000L + index * 60_000L,
            )
        )
    }

    private fun rewriteBenchmarkDownloadTasks(
        host: AppCompatActivity,
        completedDownloadTaskCount: Int,
        downloadLogBytes: Int,
    ) {
        val store = WorkshopDownloadTaskStore(host)
        val preservedTasks = store.list()
            .filterNot { it.publishedFileId.isBenchmarkPublishedFileId() }
        if (completedDownloadTaskCount <= 0) {
            store.save(preservedTasks)
            return
        }

        val downloadLog = buildBoundedDownloadLog(downloadLogBytes)
        val benchmarkTasks = List(completedDownloadTaskCount) { index ->
            val publishedFileId = BENCHMARK_PUBLISHED_FILE_ID_START + index.toULong()
            val summary = WorkshopItemSummary(
                appId = APP_ID,
                publishedFileId = publishedFileId,
                title = "Workshop Benchmark Mod $index",
                previewUrl = "https://steamuserimages-a.akamaihd.net/ugc/benchmark_${index}.jpg",
                description = "Completed workshop download task retained to reproduce mod-page refresh overhead.",
                authorName = "Benchmark Fixture",
                fileSizeBytes = 8_000_000L + index * 8192L,
                updatedAtMillis = 1_724_000_000_000L + index * 60_000L,
                downloadCount = 10_000L + index,
            )
            WorkshopDownloadTaskRecord(
                publishedFileId = publishedFileId,
                title = summary.title,
                status = WorkshopDownloadTaskStatus.Completed,
                message = "已安装 ${summary.title}",
                updatedAtMillis = summary.updatedAtMillis + 30_000L,
                details = WorkshopItemDetails(
                    summary = summary,
                    hcontentFile = publishedFileId + 1_000UL,
                    depotId = APP_ID,
                    jsonMetadata = """{"benchmark":true,"index":$index}""",
                    dependencies = buildWorkshopDependencies(index),
                ),
                previewUrl = summary.previewUrl,
                description = summary.description,
                authorName = summary.authorName,
                fileSizeBytes = summary.fileSizeBytes,
                progressPercent = 100,
                downloadedBytes = summary.fileSizeBytes,
                totalBytes = summary.fileSizeBytes,
                completedFiles = 1,
                totalFiles = 1,
                completedChunks = 16,
                totalChunks = 16,
                downloadLog = downloadLog,
            )
        }
        store.save(benchmarkTasks + preservedTasks)
    }

    private fun buildBoundedDownloadLog(targetBytes: Int): String {
        if (targetBytes <= 0) return ""
        val builder = StringBuilder(targetBytes)
        var line = 0
        while (builder.length < targetBytes) {
            builder.append("2026-06-22 21:40:")
                .append((line % 60).toString().padStart(2, '0'))
                .append(".000 benchmark completed download log line ")
                .append(line)
                .append(" with retained task metadata for mod-page lag reproduction\n")
            line++
        }
        return builder.toString().take(targetBytes)
    }

    private fun cleanupBenchmarkJars(libraryDir: File) {
        libraryDir.listFiles()
            ?.filter { file ->
                file.isFile &&
                    file.name.startsWith(BENCHMARK_FILE_PREFIX) &&
                    file.name.lowercase(Locale.ROOT).endsWith(".jar")
            }
            ?.forEach { file ->
                if (!file.delete()) {
                    Log.w(TAG, "failed to delete old benchmark jar ${file.absolutePath}")
                }
            }
    }

    private fun cleanupBenchmarkPreviewArtifacts(
        host: AppCompatActivity,
        existingBenchmarkRecords: List<WorkshopInstalledModRecord>,
        modCount: Int,
    ) {
        val publishedFileIds = linkedSetOf<ULong>()
        existingBenchmarkRecords.forEach { record -> publishedFileIds.add(record.publishedFileId) }
        repeat(modCount) { index ->
            publishedFileIds.add(BENCHMARK_PUBLISHED_FILE_ID_START + index.toULong())
        }
        val workshopRoot = File(host.filesDir, "workshop/$APP_ID")
        val cacheDirectory = File(host.filesDir, BENCHMARK_PREVIEW_CACHE_DIR)
        publishedFileIds.forEach { publishedFileId ->
            File(workshopRoot, publishedFileId.toString()).takeIf(File::exists)?.deleteRecursively()
            cacheDirectory
                .listFiles { file ->
                    file.isFile &&
                        (file.name.startsWith("$publishedFileId.") || file.name.startsWith("$publishedFileId.tmp"))
                }
                ?.forEach { file ->
                    if (!file.delete()) {
                        Log.w(TAG, "failed to delete old benchmark preview ${file.absolutePath}")
                    }
                }
        }
    }

    private fun countBenchmarkJars(libraryDir: File): Int {
        return libraryDir.listFiles()
            ?.count { file ->
                file.isFile &&
                    file.name.startsWith(BENCHMARK_FILE_PREFIX) &&
                    file.name.lowercase(Locale.ROOT).endsWith(".jar")
            }
            ?: 0
    }

    private fun countBenchmarkPreviewArtifacts(host: AppCompatActivity, modCount: Int): Int {
        val cacheDirectory = File(host.filesDir, BENCHMARK_PREVIEW_CACHE_DIR)
        return (0 until modCount).sumOf { index ->
            val publishedFileId = BENCHMARK_PUBLISHED_FILE_ID_START + index.toULong()
            val workshopPreviewFile = File(
                host.filesDir,
                "workshop/$APP_ID/$publishedFileId/$BENCHMARK_PREVIEW_FILE_NAME"
            )
            val browseCacheFile = File(cacheDirectory, "$publishedFileId.jpg")
            listOf(workshopPreviewFile, browseCacheFile).count(File::isFile)
        }
    }

    private fun hasExpectedBenchmarkDownloadTasks(
        host: AppCompatActivity,
        completedDownloadTaskCount: Int,
        downloadLogBytes: Int,
    ): Boolean {
        val benchmarkTasks = WorkshopDownloadTaskStore(host).list()
            .filter { it.publishedFileId.isBenchmarkPublishedFileId() }
        val expectedIds = (0 until completedDownloadTaskCount)
            .mapTo(LinkedHashSet()) { index -> BENCHMARK_PUBLISHED_FILE_ID_START + index.toULong() }
        val actualIds = benchmarkTasks.mapTo(LinkedHashSet()) { task -> task.publishedFileId }
        return benchmarkTasks.size == completedDownloadTaskCount &&
            actualIds == expectedIds &&
            benchmarkTasks.all { task ->
                task.status == WorkshopDownloadTaskStatus.Completed &&
                task.downloadLog.length == downloadLogBytes
            }
    }

    private fun WorkshopInstalledModRecord.isBenchmarkRecord(): Boolean {
        return appId == APP_ID && publishedFileId.isBenchmarkPublishedFileId()
    }

    private fun ULong.isBenchmarkPublishedFileId(): Boolean {
        return this >= BENCHMARK_PUBLISHED_FILE_ID_START &&
            this < BENCHMARK_PUBLISHED_FILE_ID_START + 100_000UL
    }
}

private fun String.toBenchmarkRoute(): Route? {
    return when (this) {
        "Main" -> Route.Main
        "Mods" -> Route.Mods
        "Workshop" -> Route.Workshop
        "Settings" -> Route.Settings
        else -> null
    }
}
