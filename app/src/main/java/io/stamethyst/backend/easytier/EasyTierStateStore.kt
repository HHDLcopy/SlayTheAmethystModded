package io.stamethyst.backend.easytier

import android.content.Context
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.IOException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object EasyTierStateStore {
    private const val OUTPUT_DIR_NAME = "easytier"
    private const val STATE_FILE_NAME = "connection-state.json"
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun outputDir(context: Context): File = File(RuntimePaths.storageRoot(context), OUTPUT_DIR_NAME)

    fun stateFile(context: Context): File = File(outputDir(context), STATE_FILE_NAME)

    fun readSnapshot(context: Context): EasyTierConnectionSnapshot? {
        val file = stateFile(context)
        if (!file.isFile) {
            return null
        }
        return readSnapshotFile(file)
    }

    @Throws(IOException::class)
    fun writeSnapshot(context: Context, snapshot: EasyTierConnectionSnapshot) {
        EasyTierAtomicFileStore.writeText(
            stateFile(context),
            json.encodeToString(snapshot),
            Charsets.UTF_8,
        )
    }

    fun clear(context: Context) {
        val file = stateFile(context)
        file.delete()
        EasyTierAtomicFileStore.backupFile(file).delete()
    }

    private fun readSnapshotFile(file: File): EasyTierConnectionSnapshot? {
        return try {
            decodeSnapshot(file)
        } catch (_: Throwable) {
            val backupFile = EasyTierAtomicFileStore.backupFile(file)
            if (!backupFile.isFile) {
                null
            } else {
                runCatching {
                    val snapshot = decodeSnapshot(backupFile)
                    EasyTierAtomicFileStore.writeText(file, json.encodeToString(snapshot), Charsets.UTF_8)
                    snapshot
                }.getOrNull()
            }
        }
    }

    private fun decodeSnapshot(file: File): EasyTierConnectionSnapshot =
        json.decodeFromString(file.readText(Charsets.UTF_8))
}
