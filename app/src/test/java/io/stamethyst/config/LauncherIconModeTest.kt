package io.stamethyst.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LauncherIconModeTest {
    @Test
    fun fromPersistedValue_returnsMatchingMode() {
        assertEquals(
            LauncherIconMode.AMETHYST,
            LauncherIconMode.fromPersistedValue("amethyst")
        )
        assertEquals(
            LauncherIconMode.WATCHER,
            LauncherIconMode.fromPersistedValue("watcher")
        )
    }

    @Test
    fun fromPersistedValue_mapsLegacyTemporaryModesToAmethyst() {
        assertEquals(
            LauncherIconMode.AMETHYST,
            LauncherIconMode.fromPersistedValue("follow_system")
        )
        assertEquals(
            LauncherIconMode.AMETHYST,
            LauncherIconMode.fromPersistedValue("light")
        )
        assertEquals(
            LauncherIconMode.AMETHYST,
            LauncherIconMode.fromPersistedValue("dark")
        )
    }

    @Test
    fun fromPersistedValue_returnsNullForBlankOrUnknown() {
        assertNull(LauncherIconMode.fromPersistedValue(null))
        assertNull(LauncherIconMode.fromPersistedValue(""))
        assertNull(LauncherIconMode.fromPersistedValue("unknown"))
    }
}
