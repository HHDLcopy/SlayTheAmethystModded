package io.stamethyst.backend.launch

import android.content.Context
import android.os.SystemClock
import io.stamethyst.config.RuntimePaths
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

object StartupTraceEvents {
    private val lock = Any()

    fun append(
        context: Context,
        event: String,
        extras: Map<String, Any?> = emptyMap()
    ) {
        val appContext = context.applicationContext
        val safeEvent = sanitizeToken(event)
        val payload = buildString {
            append("@amethyst.trace/")
            append(safeEvent)
            append(";elapsedRealtimeMs=")
            append(SystemClock.elapsedRealtime())
            append(";wallMs=")
            append(System.currentTimeMillis())
            for ((key, value) in extras) {
                append(';')
                append(sanitizeToken(key))
                append('=')
                append(sanitizeValue(value?.toString().orEmpty()))
            }
        }
        val line = "TRACE\t-1\t$payload\n"
        synchronized(lock) {
            runCatching {
                val file = RuntimePaths.startupTraceLog(appContext)
                file.parentFile?.mkdirs()
                FileOutputStream(file, true).use { output ->
                    output.write(line.toByteArray(StandardCharsets.UTF_8))
                    output.flush()
                }
            }
        }
    }

    private fun sanitizeToken(value: String): String {
        return value
            .trim()
            .ifBlank { "unknown" }
            .map { char ->
                if (char.isLetterOrDigit() || char == '_' || char == '-' || char == '.') {
                    char
                } else {
                    '_'
                }
            }
            .joinToString("")
    }

    private fun sanitizeValue(value: String): String {
        return value
            .replace('\t', ' ')
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
            .take(256)
    }
}
