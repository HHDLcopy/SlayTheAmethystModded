package io.stamethyst.config

enum class SpecialKeyInputMode(val persistedValue: String) {
    LEGACY_FLOATING_WINDOW("legacy_floating_window"),
    BUILT_IN_MOD("built_in_mod"),
    DISABLED("disabled");

    companion object {
        fun fromPersistedValue(value: String?): SpecialKeyInputMode? {
            if (value.isNullOrBlank()) {
                return null
            }
            return entries.firstOrNull { it.persistedValue == value.trim() }
        }
    }
}
