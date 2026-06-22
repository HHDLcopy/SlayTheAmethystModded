package io.stamethyst.backend.launch

import android.content.Context
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

object AutoplayConfigFile {
    private const val FILE_NAME = "autoplay.properties"

    private val disabledConfig = buildConfig(
        enabled = false,
        saveMode = AutoplaySaveMode.DEFAULT,
        mode = AutoplayMode.DEFAULT,
        singleRoomSpecPath = ""
    )

    @JvmStatic
    @Throws(IOException::class)
    fun syncForLaunch(
        context: Context,
        enabled: Boolean,
        saveMode: AutoplaySaveMode = AutoplaySaveMode.DEFAULT,
        mode: AutoplayMode = AutoplayMode.DEFAULT,
        singleRoomSpecPath: String = ""
    ) {
        syncForLaunch(RuntimePaths.stsRoot(context), enabled, saveMode, mode, singleRoomSpecPath)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun syncForLaunch(
        stsRoot: File,
        enabled: Boolean,
        saveMode: AutoplaySaveMode = AutoplaySaveMode.DEFAULT,
        mode: AutoplayMode = AutoplayMode.DEFAULT,
        singleRoomSpecPath: String = ""
    ) {
        val text = if (enabled) {
            buildConfig(
                enabled = true,
                saveMode = saveMode,
                mode = mode,
                singleRoomSpecPath = singleRoomSpecPath
            )
        } else {
            disabledConfig
        }
        writeConfig(File(stsRoot, FILE_NAME), text)
        writeConfig(File(File(stsRoot, "config"), FILE_NAME), text)
    }

    @Throws(IOException::class)
    private fun writeConfig(file: File, text: String) {
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create autoplay config directory: ${parent.absolutePath}")
        }
        file.writeText(text, StandardCharsets.UTF_8)
    }

    private fun buildConfig(
        enabled: Boolean,
        saveMode: AutoplaySaveMode,
        mode: AutoplayMode,
        singleRoomSpecPath: String
    ): String {
        val value = if (enabled) "true" else "false"
        return """
            |# Managed by SlayTheAmethyst at launch time.
            |# Normal launches force this off; the stsStartAutoplay debug task enables it.
            |amethyst.autoplay.enabled=$value
            |amethyst.autoplay.mode=${mode.persistedValue}
            |amethyst.autoplay.save_mode=${saveMode.persistedValue}
            |amethyst.autoplay.single_room_spec=$singleRoomSpecPath
            |amethyst.autoplay.start_run=$value
            |amethyst.autoplay.play_cards=$value
            |amethyst.autoplay.end_turn=$value
            |amethyst.autoplay.select_reward=$value
            |amethyst.autoplay.auto_navigate=$value
            |amethyst.autoplay.delay_ms=400
            |amethyst.autoplay.debug=false
            |amethyst.autoplay.reward_selection_mode=random
            |amethyst.autoplay.skip_events=false
            |amethyst.autoplay.skip_shops=false
            |amethyst.autoplay.skip_chests=false
            |
        """.trimMargin()
    }
}
