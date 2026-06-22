package io.stamethyst.backend.launch

enum class AutoplayMode(val persistedValue: String) {
    NORMAL("normal"),
    SINGLE_ROOM("single_room");

    companion object {
        val DEFAULT: AutoplayMode = NORMAL

        fun fromPersistedValue(value: String?): AutoplayMode {
            val normalized = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.persistedValue == normalized }
                ?: DEFAULT
        }
    }
}
