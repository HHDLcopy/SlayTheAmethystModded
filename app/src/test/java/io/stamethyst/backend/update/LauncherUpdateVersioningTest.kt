package io.stamethyst.backend.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherUpdateVersioningTest {
    @Test
    fun isRemoteNewer_treatsHotfixAsNewerThanBasePatch() {
        assertTrue(
            LauncherUpdateVersioning.isRemoteNewer(
                currentVersion = "1.0.6",
                remoteVersionTag = "1.0.6-hotfix1"
            )
        )
    }

    @Test
    fun isRemoteNewer_ordersHotfixesNumerically() {
        assertTrue(
            LauncherUpdateVersioning.isRemoteNewer(
                currentVersion = "1.0.6-hotfix1",
                remoteVersionTag = "1.0.6-hotfix2"
            )
        )
    }

    @Test
    fun isRemoteNewer_prefersHigherPatchOverHotfix() {
        assertTrue(
            LauncherUpdateVersioning.isRemoteNewer(
                currentVersion = "1.0.6-hotfix1",
                remoteVersionTag = "1.0.7"
            )
        )
    }

    @Test
    fun isRemoteNewer_ordersDevStableAndHotfixStages() {
        assertTrue(
            LauncherUpdateVersioning.isRemoteNewer(
                currentVersion = "1.4.13-dev1",
                remoteVersionTag = "1.4.13-dev2"
            )
        )
        assertTrue(
            LauncherUpdateVersioning.isRemoteNewer(
                currentVersion = "1.4.13-dev2",
                remoteVersionTag = "1.4.13"
            )
        )
        assertTrue(
            LauncherUpdateVersioning.isRemoteNewer(
                currentVersion = "1.4.13",
                remoteVersionTag = "1.4.13-hotfix1"
            )
        )
        assertFalse(
            LauncherUpdateVersioning.isRemoteNewer(
                currentVersion = "1.4.13",
                remoteVersionTag = "1.4.13-dev2"
            )
        )
        assertFalse(
            LauncherUpdateVersioning.isRemoteNewer(
                currentVersion = "1.4.13-hotfix1",
                remoteVersionTag = "1.4.13"
            )
        )
    }

    @Test
    fun compareReleaseVersions_ordersReleaseTagsAndReturnsNullForUnknownTags() {
        assertEquals(
            -1,
            LauncherUpdateVersioning.compareReleaseVersions("v1.3.1", "1.3.2")
        )
        assertEquals(
            0,
            LauncherUpdateVersioning.compareReleaseVersions("v1.3.1", "1.3.1")
        )
        assertEquals(
            1,
            LauncherUpdateVersioning.compareReleaseVersions("1.3.2", "v1.3.1")
        )
        assertNull(LauncherUpdateVersioning.compareReleaseVersions("nightly", "v1.3.1"))
    }

    @Test
    fun releaseVersionFamilyKey_ignoresHotfixSuffix() {
        assertEquals("1.3.2", LauncherUpdateVersioning.releaseVersionFamilyKey("v1.3.2"))
        assertEquals("1.3.2", LauncherUpdateVersioning.releaseVersionFamilyKey("1.3.2-dev1"))
        assertEquals("1.3.2", LauncherUpdateVersioning.releaseVersionFamilyKey("1.3.2-hotfix2"))
        assertNull(LauncherUpdateVersioning.releaseVersionFamilyKey("nightly"))
    }

    @Test
    fun isRemoteNewer_fallsBackToNormalizedStringComparisonForInvalidTags() {
        assertFalse(
            LauncherUpdateVersioning.isRemoteNewer(
                currentVersion = "nightly",
                remoteVersionTag = "vnightly"
            )
        )
        assertTrue(
            LauncherUpdateVersioning.isRemoteNewer(
                currentVersion = "nightly",
                remoteVersionTag = "nightly-2"
            )
        )
    }

    @Test
    fun normalizeReleaseNotesText_preservesFullBodyAndMarkdownStructure() {
        assertEquals(
            "# Fixes\n- one\n\n## More\n- two",
            LauncherUpdateVersioning.normalizeReleaseNotesText(
                "\uFEFF# Fixes\r\n- one\r\n\r\n## More\r\n- two\r\n"
            )
        )
    }
}
