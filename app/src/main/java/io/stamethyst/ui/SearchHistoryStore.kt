package io.stamethyst.ui

import android.content.Context
import org.json.JSONArray

internal object SearchHistoryStore {
    private const val PREFS_NAME = "search_history"
    private const val KEY_MODS = "mods"
    private const val KEY_WORKSHOP = "workshop"
    internal const val MAX_ENTRIES = 8

    fun loadModSearchHistory(context: Context): List<String> {
        return load(context, KEY_MODS)
    }

    fun recordModSearch(context: Context, query: String): List<String> {
        return record(context, KEY_MODS, query)
    }

    fun loadWorkshopSearchHistory(context: Context): List<String> {
        return load(context, KEY_WORKSHOP)
    }

    fun recordWorkshopSearch(context: Context, query: String): List<String> {
        return record(context, KEY_WORKSHOP, query)
    }

    private fun record(context: Context, key: String, query: String): List<String> {
        val updated = mergeSearchHistory(load(context, key), query)
        save(context, key, updated)
        return updated
    }

    private fun load(context: Context, key: String): List<String> {
        val raw = prefs(context).getString(key, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList<String> {
                for (index in 0 until array.length()) {
                    val entry = array.optString(index).trim()
                    if (entry.isNotEmpty() && this.none { it.equals(entry, ignoreCase = true) }) {
                        add(entry)
                    }
                    if (size >= MAX_ENTRIES) {
                        break
                    }
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun save(context: Context, key: String, entries: List<String>) {
        val array = JSONArray()
        entries.take(MAX_ENTRIES).forEach(array::put)
        prefs(context).edit()
            .putString(key, array.toString())
            .apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

internal fun mergeSearchHistory(
    existing: List<String>,
    query: String,
    limit: Int = SearchHistoryStore.MAX_ENTRIES,
): List<String> {
    val normalized = query.trim()
    if (normalized.isEmpty()) {
        return existing.take(limit)
    }
    return buildList<String> {
        add(normalized)
        existing.forEach { entry ->
            val trimmed = entry.trim()
            if (trimmed.isNotEmpty() && this.none { it.equals(trimmed, ignoreCase = true) }) {
                add(trimmed)
            }
            if (size >= limit) {
                return@buildList
            }
        }
    }
}
