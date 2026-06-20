package io.stamethyst.backend.launch

enum class AutoplaySaveMode(val persistedValue: String) {
    FRESH("fresh"),
    CONTINUE("continue");

    companion object {
        val DEFAULT: AutoplaySaveMode = FRESH

        @JvmStatic
        fun fromPersistedValue(value: String?): AutoplaySaveMode {
            val normalized = value?.trim()?.lowercase().orEmpty()
            return values().firstOrNull { it.persistedValue == normalized } ?: DEFAULT
        }

        @JvmStatic
        fun sanitizePersistedValue(value: String?): String {
            return fromPersistedValue(value).persistedValue
        }
    }
}
