package io.stamethyst.backend.workshop

import android.content.Context
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object WorkshopBrowseFailureLogStore {
    const val MAX_LOG_SLOTS = 5

    private const val LOG_FILE_PREFIX = "browse_failure_"
    private const val LOG_FILE_SUFFIX = ".log.txt"
    private val lock = Any()

    fun writeFailure(
        context: Context,
        query: WorkshopBrowseQuery,
        page: Int,
        append: Boolean,
        elapsedMs: Long,
        error: Throwable,
    ) {
        runCatching {
            synchronized(lock) {
                val logsDir = RuntimePaths.workshopBrowseFailureLogsDir(context)
                ensureDirectory(logsDir)
                val logFile = allocateLogFile(logsDir)
                logFile.writeText(
                    buildFailureLogText(
                        query = query,
                        page = page,
                        append = append,
                        elapsedMs = elapsedMs,
                        error = error,
                    ),
                    StandardCharsets.UTF_8
                )
                pruneOldLogs(logsDir)
            }
        }
    }

    fun listLogFiles(context: Context): List<File> {
        val logsDir = RuntimePaths.workshopBrowseFailureLogsDir(context)
        if (!logsDir.isDirectory) {
            return emptyList()
        }
        return enumerateLogFiles(logsDir).sortedByDescending { it.name }
    }

    private fun buildFailureLogText(
        query: WorkshopBrowseQuery,
        page: Int,
        append: Boolean,
        elapsedMs: Long,
        error: Throwable,
    ): String = buildString {
        appendLine("Workshop browse failure log")
        appendLine("Recorded At: ${timestamp()}")
        appendLine("App ID: ${query.appId}")
        appendLine("Page: $page")
        appendLine("Append Mode: $append")
        appendLine("Elapsed Ms: ${elapsedMs.coerceAtLeast(0L)}")
        appendLine("Search Text: ${query.searchText.ifBlank { "<empty>" }}")
        appendLine("Sort: ${query.sort}")
        appendLine("Time Filter: ${query.timeFilter}")
        appendLine("Category: ${query.category}")
        appendLine("Requested Page Size: ${query.pageSize}")
        appendLine("Error Type: ${error.javaClass.name}")
        appendLine("Error Message: ${error.message?.trim().takeUnless { it.isNullOrEmpty() } ?: "<empty>"}")
        appendLine()
        appendLine("Stack Trace:")
        appendLine(stackTraceText(error))
    }.trimEnd() + "\n"

    private fun ensureDirectory(directory: File) {
        if (directory.isDirectory) {
            return
        }
        if (!directory.exists() && directory.mkdirs()) {
            return
        }
        if (!directory.isDirectory) {
            throw IOException("Failed to create workshop browse failure log directory: ${directory.absolutePath}")
        }
    }

    private fun allocateLogFile(logsDir: File): File {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
        val baseName = formatter.format(Date())
        var sequence = 0
        while (sequence < 20) {
            val suffix = "-${sequence.toString().padStart(2, '0')}"
            val candidate = File(logsDir, "${LOG_FILE_PREFIX}${baseName}${suffix}${LOG_FILE_SUFFIX}")
            if (candidate.createNewFile()) {
                return candidate
            }
            sequence++
        }
        throw IOException("Failed to allocate workshop browse failure log slot in ${logsDir.absolutePath}")
    }

    private fun pruneOldLogs(logsDir: File) {
        val files = enumerateLogFiles(logsDir).sortedBy { it.name }
        if (files.size <= MAX_LOG_SLOTS) {
            return
        }
        files.take(files.size - MAX_LOG_SLOTS).forEach { it.delete() }
    }

    private fun enumerateLogFiles(logsDir: File): List<File> {
        return logsDir.listFiles()
            .orEmpty()
            .filter { file ->
                file.isFile &&
                    file.name.startsWith(LOG_FILE_PREFIX) &&
                    file.name.endsWith(LOG_FILE_SUFFIX)
            }
    }

    private fun stackTraceText(error: Throwable): String {
        val writer = StringWriter()
        PrintWriter(writer).use { printWriter ->
            error.printStackTrace(printWriter)
            printWriter.flush()
        }
        return writer.toString().trimEnd()
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
