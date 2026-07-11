package io.stamethyst.backend.easytier

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object EasyTierRoomSelectionStore {
    private const val FILE_NAME = "room-selection.json"
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun read(context: Context): EasyTierRoomSelectionSnapshot {
        val file = file(context)
        if (!file.isFile) {
            return EasyTierRoomSelectionSnapshot()
        }
        return runCatching {
            json.decodeFromString<EasyTierRoomSelectionSnapshot>(file.readText(Charsets.UTF_8))
        }.getOrElse {
            EasyTierRoomSelectionSnapshot()
        }
    }

    @Throws(IOException::class)
    fun write(
        context: Context,
        snapshot: EasyTierRoomSelectionSnapshot,
    ) {
        EasyTierAtomicFileStore.writeText(
            file(context),
            json.encodeToString(snapshot),
            Charsets.UTF_8,
        )
    }

    private fun file(context: Context): File =
        File(EasyTierStateStore.outputDir(context), FILE_NAME)
}

@Serializable
internal data class EasyTierRoomSelectionSnapshot(
    val preferredRoomId: String = "",
)
