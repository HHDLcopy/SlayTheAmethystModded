package io.stamethyst.backend.launch

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class AutoplayConfigFileTest {
    @Test
    fun syncForLaunch_disablesStaleAutoplayConfigForNormalLaunch() {
        val root = Files.createTempDirectory("autoplay-config-normal").toFile()
        try {
            File(root, "autoplay.properties").writeText(
                "amethyst.autoplay.enabled=true\namethyst.autoplay.end_turn=true\n",
                StandardCharsets.UTF_8
            )
            File(root, "config").mkdirs()
            File(root, "config/autoplay.properties").writeText(
                "amethyst.autoplay.enabled=true\namethyst.autoplay.end_turn=true\n",
                StandardCharsets.UTF_8
            )

            AutoplayConfigFile.syncForLaunch(root, enabled = false)

            assertAutoplayValue(File(root, "autoplay.properties"), "false")
            assertAutoplayValue(File(root, "config/autoplay.properties"), "false")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun syncForLaunch_enablesAutoplayConfigForDebugLaunch() {
        val root = Files.createTempDirectory("autoplay-config-debug").toFile()
        try {
            AutoplayConfigFile.syncForLaunch(root, enabled = true)

            assertAutoplayValue(File(root, "autoplay.properties"), "true")
            assertAutoplayValue(File(root, "config/autoplay.properties"), "true")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun syncForLaunch_writesAutoplaySaveModeForDebugLaunch() {
        val root = Files.createTempDirectory("autoplay-config-save-mode").toFile()
        try {
            AutoplayConfigFile.syncForLaunch(
                root,
                enabled = true,
                saveMode = AutoplaySaveMode.CONTINUE
            )

            assertAutoplaySaveMode(File(root, "autoplay.properties"), "continue")
            assertAutoplaySaveMode(File(root, "config/autoplay.properties"), "continue")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun syncForLaunch_writesSingleRoomModeForDebugLaunch() {
        val root = Files.createTempDirectory("autoplay-config-single-room").toFile()
        try {
            AutoplayConfigFile.syncForLaunch(
                root,
                enabled = true,
                mode = AutoplayMode.SINGLE_ROOM,
                singleRoomSpecPath = "files/sts/config/autoplay-single-room.properties"
            )

            assertAutoplayMode(File(root, "autoplay.properties"), "single_room")
            assertAutoplaySingleRoomSpec(
                File(root, "config/autoplay.properties"),
                "files/sts/config/autoplay-single-room.properties"
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertAutoplayValue(file: File, value: String) {
        val text = file.readText(StandardCharsets.UTF_8)
        assertTrue(text.contains("amethyst.autoplay.enabled=$value"))
        assertTrue(text.contains("amethyst.autoplay.start_run=$value"))
        assertTrue(text.contains("amethyst.autoplay.play_cards=$value"))
        assertTrue(text.contains("amethyst.autoplay.end_turn=$value"))
        assertTrue(text.contains("amethyst.autoplay.select_reward=$value"))
        assertTrue(text.contains("amethyst.autoplay.auto_navigate=$value"))
    }

    private fun assertAutoplaySaveMode(file: File, value: String) {
        val text = file.readText(StandardCharsets.UTF_8)
        assertTrue(text.contains("amethyst.autoplay.save_mode=$value"))
    }

    private fun assertAutoplayMode(file: File, value: String) {
        val text = file.readText(StandardCharsets.UTF_8)
        assertTrue(text.contains("amethyst.autoplay.mode=$value"))
    }

    private fun assertAutoplaySingleRoomSpec(file: File, value: String) {
        val text = file.readText(StandardCharsets.UTF_8)
        assertTrue(text.contains("amethyst.autoplay.single_room_spec=$value"))
    }
}
