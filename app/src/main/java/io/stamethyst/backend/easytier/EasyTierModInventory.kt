package io.stamethyst.backend.easytier

import android.content.Context
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.backend.workshop.WorkshopInstalledModRecord
import io.stamethyst.backend.workshop.WorkshopMetadataStore
import io.stamethyst.backend.workshop.allLocalJarPaths
import java.io.File
import java.util.Locale

internal object EasyTierModInventory {
    fun collect(context: Context): List<EasyTierRoomMod> {
        val appContext = context.applicationContext
        val workshopByJarPath = buildMap {
            WorkshopMetadataStore(appContext).list().forEach { record ->
                resolveWorkshopJarPaths(appContext, record).forEach { path ->
                    put(path, record)
                }
            }
        }
        return buildReportedMods(
            installedMods = ModManager.listInstalledMods(appContext),
            workshopByJarPath = workshopByJarPath,
        )
    }

    internal fun buildReportedMods(
        installedMods: List<ModManager.InstalledMod>,
        workshopByJarPath: Map<String, WorkshopInstalledModRecord>,
    ): List<EasyTierRoomMod> {
        val seen = linkedSetOf<String>()
        return installedMods.asSequence()
            .filter { mod -> mod.installed && mod.enabled && !mod.required }
            .mapNotNull { mod ->
                val name = mod.name.trim().ifBlank { mod.jarFile.nameWithoutExtension.trim() }
                if (name.isBlank()) {
                    return@mapNotNull null
                }
                val workshopId = workshopByJarPath[mod.jarFile.absolutePath]
                    ?.publishedFileId
                    ?.toString()
                    .orEmpty()
                val dedupeKey = if (workshopId.isBlank()) {
                    "local:${name.lowercase(Locale.ROOT)}"
                } else {
                    "workshop:$workshopId"
                }
                if (!seen.add(dedupeKey)) {
                    return@mapNotNull null
                }
                EasyTierRoomMod(name = name, workshopId = workshopId)
            }
            .take(MAX_REPORTED_MODS)
            .toList()
    }

    private fun resolveWorkshopJarPaths(
        context: Context,
        record: WorkshopInstalledModRecord,
    ): List<String> = record.allLocalJarPaths()
        .map { rawPath ->
            val file = File(rawPath)
            if (file.isAbsolute) {
                file.absolutePath
            } else {
                File(
                    context.filesDir,
                    "workshop/${record.appId}/${record.publishedFileId}/$rawPath",
                ).absolutePath
            }
        }
        .distinct()

    private const val MAX_REPORTED_MODS = 128
}
