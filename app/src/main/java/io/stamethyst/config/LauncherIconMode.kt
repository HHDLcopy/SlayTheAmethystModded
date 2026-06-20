package io.stamethyst.config

enum class LauncherIconMode(
    val persistedValue: String
) {
    AMETHYST("amethyst"),
    WATCHER("watcher");

    companion object {
        @JvmStatic
        fun fromPersistedValue(value: String?): LauncherIconMode? {
            if (value.isNullOrBlank()) {
                return null
            }
            val normalized = value.trim()
            return entries.firstOrNull { it.persistedValue == normalized } ?: when (normalized) {
                "follow_system", "light", "dark" -> AMETHYST
                else -> null
            }
        }
    }
}
