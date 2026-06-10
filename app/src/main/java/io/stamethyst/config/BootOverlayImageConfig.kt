package io.stamethyst.config

enum class BootOverlayImageMode(
    val persistedValue: String
) {
    SINGLE("single"),
    DUAL("dual");

    companion object {
        fun fromPersistedValue(value: String?): BootOverlayImageMode? {
            return entries.firstOrNull { it.persistedValue == value }
        }
    }
}

enum class BootOverlayImageSlot(
    val fileName: String
) {
    START("boot_overlay_start.image"),
    END("boot_overlay_end.image")
}

data class BootOverlayImageConfig(
    val mode: BootOverlayImageMode = BootOverlayImageMode.DUAL,
    val startImagePath: String? = null,
    val endImagePath: String? = null,
    val startImageVersion: Long = 0L,
    val endImageVersion: Long = 0L
) {
    val hasCustomImages: Boolean
        get() = !startImagePath.isNullOrBlank() || !endImagePath.isNullOrBlank()

    fun imagePathFor(slot: BootOverlayImageSlot): String? {
        return when (slot) {
            BootOverlayImageSlot.START -> startImagePath
            BootOverlayImageSlot.END -> endImagePath
        }
    }

    fun resolvedStartImagePath(): String? {
        return startImagePath
    }

    fun resolvedEndImagePath(): String? {
        return if (mode == BootOverlayImageMode.SINGLE) {
            startImagePath
        } else {
            endImagePath
        }
    }

    fun resolvedStartImageVersion(): Long {
        return startImageVersion
    }

    fun resolvedEndImageVersion(): Long {
        return if (mode == BootOverlayImageMode.SINGLE) {
            startImageVersion
        } else {
            endImageVersion
        }
    }
}
