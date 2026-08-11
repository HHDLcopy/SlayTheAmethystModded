package io.stamethyst.backend.steamcloud

import android.content.Context
import io.stamethyst.config.RuntimePaths
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Synchronizes achievement state produced by the game runtime in the launcher process. */
object SteamAchievementSyncService {
    private const val PREFS = "steam_achievement_sync"
    private const val PENDING_IDS = "pending_ids"
    private const val FILE_NAME = "STSAchievements"
    private const val REQUEST_VERSION = 1
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    data class Request(val id: String, val achievementIds: Set<String>, val saveSlot: Int? = null) {
        val dedupeKey: String get() = "$id:$saveSlot"
    }

    data class SyncPlan(val upload: Set<String>, val localFiles: List<File>)

    fun parseRequest(text: String): Request? = runCatching {
        val json = JSONObject(text.trim())
        if (json.optInt("version", REQUEST_VERSION) != REQUEST_VERSION) return null
        val ids = buildSet {
            val array = json.optJSONArray("achievements")
            if (array != null) for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf { it in SteamAchievementCatalog.apiNames }?.let(::add)
            }
            json.optString("achievement").trim().takeIf { it in SteamAchievementCatalog.apiNames }?.let(::add)
        }
        val id = json.optString("request_id").trim()
            .ifBlank { json.optString("id").trim() }
            .ifBlank { ids.sorted().joinToString(",") }
        if (id.isBlank() || ids.isEmpty()) return null
        val slot = if (json.has("save_slot") && !json.isNull("save_slot")) {
            json.optInt("save_slot", -1).takeIf { it in 0..2 }
        } else null
        Request(id, ids, slot)
    }.getOrNull()

    fun pendingIds(context: Context): Set<String> = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet(PENDING_IDS, emptySet()).orEmpty().toSet()

    fun syncRequestAsync(context: Context, request: Request, onFinished: (Throwable?) -> Unit = {}) {
        executor.execute {
            val error = runCatching { syncRequest(context.applicationContext, request) }.exceptionOrNull()
            onFinished(error)
        }
    }

    /** Reconciles the union of all three local achievement files with the current Steam state. */
    fun syncAllLocalAchievementsAsync(context: Context, onFinished: (Throwable?) -> Unit = {}) {
        executor.execute {
            val error = runCatching { syncLocalAchievements(context.applicationContext) }.exceptionOrNull()
            onFinished(error)
        }
    }

    fun retryPendingUploadsAsync(context: Context, onFinished: (Throwable?) -> Unit = {}) {
        syncAllLocalAchievementsAsync(context, onFinished)
    }

    /** Clears a remotely locked achievement from every existing local achievement preference file. */
    fun lockAchievementInAllLocalSaves(context: Context, apiName: String) {
        val normalizedApiName = apiName.trim().lowercase()
        require(normalizedApiName in SteamAchievementCatalog.apiNames) {
            "Unknown Steam achievement: $apiName"
        }
        lockAchievementInFiles(achievementFiles(context), normalizedApiName)
        removePending(context, normalizedApiName)
        val commandFile = RuntimePaths.achievementLockCommandFile(context)
        val queuedCommands = runCatching {
            commandFile.takeIf { it.isFile }
                ?.readLines()
                .orEmpty()
                .map { it.trim().lowercase() }
                .filter { it in SteamAchievementCatalog.apiNames }
                .toMutableSet()
        }.getOrDefault(mutableSetOf())
        queuedCommands += normalizedApiName
        SteamCloudAtomicFileStore.writeText(commandFile, queuedCommands.sorted().joinToString("\n"))
    }

    internal fun plan(localUnlocked: Set<String>, remoteUnlocked: Set<String>, files: List<File>): SyncPlan =
        SyncPlan(localUnlocked - remoteUnlocked, files)

    /** Reads all local save slots and returns achievements missing from the supplied Steam snapshot. */
    internal fun localAchievementsMissingFromSteam(
        context: Context,
        remoteUnlocked: Set<String>,
    ): Set<String> = localAchievementsMissingFromSteam(achievementFiles(context), remoteUnlocked)

    internal fun localAchievementsMissingFromSteam(
        files: List<File>,
        remoteUnlocked: Set<String>,
    ): Set<String> {
        val localUnlocked = files.flatMap { readUnlocked(it).asSequence() }.toSet()
        return plan(localUnlocked, remoteUnlocked, files).upload
    }

    private fun syncRequest(context: Context, request: Request) {
        syncLocalAchievements(context)
    }

    private fun syncLocalAchievements(context: Context) {
        val auth = SteamCloudAuthStore.readAuthMaterial(context)
            ?: error("Steam authentication is unavailable")
        val remote = SteamAchievementService.fetchViaCm(
            context, auth.accountName, auth.refreshToken, auth.steamId64
        ).achievements.filter { it.unlocked }.map { it.apiName }.toSet()
        val upload = localAchievementsMissingFromSteam(context, remote)
        upload.forEach { addPending(context, it) }
        upload.forEach { apiName ->
            try {
                SteamAchievementService.setAchievementUnlockedViaCm(
                    context, auth.accountName, auth.refreshToken, auth.steamId64, apiName, true
                )
                removePending(context, apiName)
            } catch (error: Throwable) {
                throw error
            }
        }
    }

    private fun achievementFiles(context: Context): List<File> {
        val dir = RuntimePaths.preferencesDir(context)
        return listOf(FILE_NAME, "1_$FILE_NAME", "2_$FILE_NAME").map { File(dir, it) }
    }

    internal fun readUnlocked(file: File): Set<String> = runCatching {
        if (!file.isFile) return emptySet()
        val json = JSONObject(file.readText())
        json.keys().asSequence().mapNotNull { storedKey ->
            storedKey.lowercase().takeIf { apiName ->
                apiName in SteamAchievementCatalog.apiNames && isUnlockedValue(json.opt(storedKey))
            }
        }.toSet()
    }.getOrDefault(emptySet())

    internal fun lockAchievementInFiles(files: List<File>, apiName: String) {
        files.forEach { file ->
            if (!file.isFile) return@forEach
            val json = JSONObject(file.readText())
            val storedKey = json.keys().asSequence().firstOrNull { it.equals(apiName, ignoreCase = true) }
            if (storedKey != null) {
                json.remove(storedKey)
                SteamCloudAtomicFileStore.writeText(file, json.toString())
            }
        }
    }

    private fun isUnlockedValue(value: Any?): Boolean = when (value) {
        is Number -> value.toInt() != 0
        is Boolean -> value
        is String -> value == "1" || value.equals("true", ignoreCase = true)
        else -> false
    }

    private fun addPending(context: Context, apiName: String) {
        val ids = pendingIds(context) + apiName
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(PENDING_IDS, ids).apply()
    }

    private fun removePending(context: Context, apiName: String) {
        val ids = pendingIds(context) - apiName
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(PENDING_IDS, ids).apply()
    }
}
