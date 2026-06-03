package io.stamethyst.ui.main

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import io.stamethyst.LauncherActivity
import io.stamethyst.R
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.backend.workshop.WorkshopMetadataStore
import io.stamethyst.backend.workshop.WorkshopModCardState
import io.stamethyst.config.RuntimePaths
import io.stamethyst.ui.preferences.LauncherPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class LauncherLargeModSetInstrumentedTest {
    private lateinit var context: Context
    private lateinit var device: UiDevice
    private val backups = ArrayList<DirectoryBackup>()
    private var previousFirstRunSetupCompleted = false
    private var previousBasicTutorialNoticeDismissed = false
    private var previousDeveloperSettingsWarningDismissed = false
    private var previousLastWorkshopUpdateCheckAtMs = 0L

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext.applicationContext
        device = UiDevice.getInstance(instrumentation)
        previousFirstRunSetupCompleted = LauncherPreferences.isFirstRunSetupCompleted(context)
        previousBasicTutorialNoticeDismissed = LauncherPreferences.isBasicTutorialNoticeDismissed(context)
        previousDeveloperSettingsWarningDismissed = LauncherPreferences.isDeveloperSettingsWarningDismissed(context)
        previousLastWorkshopUpdateCheckAtMs = LauncherPreferences.readLastWorkshopUpdateCheckAtMs(context)

        backupTestOwnedDirectories()
        seedLargeModSet()
    }

    @After
    fun tearDown() {
        runCatching { device.pressHome() }
        LauncherPreferences.setFirstRunSetupCompleted(context, previousFirstRunSetupCompleted)
        LauncherPreferences.setBasicTutorialNoticeDismissed(context, previousBasicTutorialNoticeDismissed)
        LauncherPreferences.setDeveloperSettingsWarningDismissed(context, previousDeveloperSettingsWarningDismissed)
        LauncherPreferences.saveLastWorkshopUpdateCheckAtMs(context, previousLastWorkshopUpdateCheckAtMs)
        backups.asReversed().forEach { backup ->
            runCatching { backup.restore() }
        }
        backups.clear()
    }

    @Test
    fun launcherReachesModsScreenWithLargeOptionalIndexAndWorkshopInstalledMods() {
        runCatching { device.wakeUp() }
        val launchResult = launchLauncherActivity()

        val appForeground = device.wait(
            Until.hasObject(By.pkg(context.packageName)),
            APP_FOREGROUND_TIMEOUT_MS
        )
        assertTrue(
            buildUiTimeoutMessage(
                prefix = "Launcher was not brought to foreground",
                timeoutMs = APP_FOREGROUND_TIMEOUT_MS,
                launchResult = launchResult
            ),
            appForeground
        )

        val modsDock = waitForTestTag(
            LAUNCHER_DOCK_MODS_TAG,
            LAUNCH_TIMEOUT_MS
        ) ?: throw AssertionError(
            buildUiTimeoutMessage(
                prefix = "Mods dock was not reachable",
                timeoutMs = LAUNCH_TIMEOUT_MS,
                launchResult = launchResult
            )
        )

        modsDock.click()

        val reachedReadyState = waitForTestTag(
            MODS_CONTENT_READY_TAG,
            MODS_READY_TIMEOUT_MS
        ) != null
        assertTrue(
            buildUiTimeoutMessage(
                prefix = "Mods content did not become ready",
                timeoutMs = MODS_READY_TIMEOUT_MS,
                launchResult = launchResult
            ),
            reachedReadyState
        )
    }

    private fun launchLauncherActivity(): String {
        val component = "${context.packageName}/${LauncherActivity::class.java.name}"
        return device.executeShellCommand(
            "am start -W -a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER -n $component"
        )
    }

    private fun waitForTestTag(tag: String, timeoutMs: Long): UiObject2? {
        val packageSelector = By.res(context.packageName, tag)
        val rawSelector = By.res(tag)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            dismissKnownBlockingDialogs()
            device.findObject(packageSelector)?.let { return it }
            device.findObject(rawSelector)?.let { return it }
            Thread.sleep(100L)
        }
        return null
    }

    private fun dismissKnownBlockingDialogs() {
        val suggestionUpdateTitle = context.getString(R.string.main_mod_suggestion_update_title)
        if (device.findObject(By.text(suggestionUpdateTitle)) == null) {
            return
        }
        val confirmText = context.getString(R.string.common_action_confirm)
        device.findObject(By.text(confirmText))?.click()
        device.wait(Until.gone(By.text(suggestionUpdateTitle)), DIALOG_DISMISS_TIMEOUT_MS)
    }

    private fun backupTestOwnedDirectories() {
        val candidates = listOf(
            RuntimePaths.stsRoot(context),
            RuntimePaths.legacyInternalStsRoot(context),
            File(context.filesDir, "workshop")
        )
        val seen = LinkedHashSet<String>()
        candidates.forEach { directory ->
            val key = directory.absolutePath
            if (seen.add(key)) {
                backups += DirectoryBackup.take(directory)
            }
        }
    }

    private fun seedLargeModSet() {
        RuntimePaths.ensureBaseDirs(context)
        writeValidStsJar(RuntimePaths.importedStsJar(context))

        val libraryDir = RuntimePaths.optionalModsLibraryDir(context)
        val optionalJars = (1..OPTIONAL_MOD_COUNT).map { index ->
            writeOptionalModJar(
                file = File(libraryDir, "LargeMod%03d.jar".format(Locale.US, index)),
                modId = "large_mod_$index",
                name = "Large Mod $index",
                lastModified = 100_000L + index
            )
        }

        writeWorkshopMetadata(optionalJars.take(WORKSHOP_INSTALLED_COUNT))
        LauncherPreferences.setFirstRunSetupCompleted(context, true)
        LauncherPreferences.setBasicTutorialNoticeDismissed(context, true)
        LauncherPreferences.setDeveloperSettingsWarningDismissed(context, true)
        LauncherPreferences.saveLastWorkshopUpdateCheckAtMs(context, System.currentTimeMillis())

        val optionalMods = ModManager.listInstalledMods(context).filterNot { it.required }
        assertEquals(OPTIONAL_MOD_COUNT, optionalMods.size)
        assertTrue(RuntimePaths.optionalModIndexFile(context).isFile)

        val workshopRecords = WorkshopMetadataStore(context).list()
        assertEquals(WORKSHOP_INSTALLED_COUNT, workshopRecords.size)
        assertTrue(
            workshopRecords.all { record ->
                record.cardState == WorkshopModCardState.ImportedPatched &&
                    record.localJarPaths.size == 1 &&
                    File(record.localJarPaths.single()).isFile
            }
        )
    }

    private fun writeValidStsJar(file: File) {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zip.write(
                "Manifest-Version: 1.0\nMain-Class: com.megacrit.cardcrawl.desktop.DesktopLauncher\n\n"
                    .toByteArray(StandardCharsets.UTF_8)
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("com/megacrit/cardcrawl/desktop/DesktopLauncher.class"))
            zip.write(byteArrayOf(0))
            zip.closeEntry()
        }
    }

    private fun writeOptionalModJar(
        file: File,
        modId: String,
        name: String,
        lastModified: Long
    ): File {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("ModTheSpire.json"))
            val manifest = JSONObject()
                .put("modid", modId)
                .put("name", name)
                .put("version", "1.0.0")
                .put("description", "Large optional mod fixture")
                .put("dependencies", JSONArray())
            zip.write(manifest.toString().toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }
        file.setLastModified(lastModified)
        return file
    }

    private fun writeWorkshopMetadata(jars: List<File>) {
        val workshopDir = File(context.filesDir, "workshop").apply { mkdirs() }
        val items = JSONArray()
        val now = System.currentTimeMillis()
        jars.forEachIndexed { index, jar ->
            val publishedFileId = 4_200_000_000L + index
            items.put(
                JSONObject()
                    .put("appId", 646570L)
                    .put("publishedFileId", publishedFileId.toString())
                    .put("title", "Workshop Large Mod ${index + 1}")
                    .put("description", "Market-installed large optional mod fixture")
                    .put("previewUrl", "")
                    .put("versionText", now.toString())
                    .put("updatedAtMillis", now)
                    .put("installedAtMillis", now)
                    .put("localJarPath", jar.absolutePath)
                    .put("localJarPaths", JSONArray().put(jar.absolutePath))
                    .put("contentKind", "JarMod")
                    .put("texturePackPath", "")
                    .put("cardState", "ImportedPatched")
                    .put("statusText", "已安装")
                    .put("localPreviewImagePath", "")
                    .put("dependencies", JSONArray())
            )
        }
        File(workshopDir, "index.json").writeText(
            JSONObject().put("items", items).toString(2),
            StandardCharsets.UTF_8
        )
    }

    private fun buildUiTimeoutMessage(
        prefix: String,
        timeoutMs: Long,
        launchResult: String? = null
    ): String {
        val hierarchy = runCatching {
            ByteArrayOutputStream().use { output ->
                device.dumpWindowHierarchy(output)
                output.toString(StandardCharsets.UTF_8.name())
            }
        }.getOrElse { error ->
            "Unable to dump UI hierarchy: ${error.message}"
        }
        val pid = runCatching { device.executeShellCommand("pidof ${context.packageName}") }
            .getOrDefault("")
            .trim()
        val launchDetails = launchResult?.let { " launchResult=$it" }.orEmpty()
        return "$prefix after ${timeoutMs}ms. targetPackage=${context.packageName} pid=$pid$launchDetails hierarchy=$hierarchy"
    }

    private data class DirectoryBackup(
        val original: File,
        val backup: File,
        val existed: Boolean
    ) {
        fun restore() {
            original.deleteRecursively()
            if (existed) {
                backup.parentFile?.mkdirs()
                check(backup.renameTo(original)) {
                    "Failed to restore ${backup.absolutePath} to ${original.absolutePath}"
                }
            } else {
                backup.deleteRecursively()
            }
        }

        companion object {
            fun take(original: File): DirectoryBackup {
                val backup = File(
                    original.parentFile,
                    "${original.name}.large-mod-test-backup-${System.currentTimeMillis()}-${System.nanoTime()}"
                )
                if (backup.exists()) {
                    backup.deleteRecursively()
                }
                val existed = original.exists()
                if (existed) {
                    check(original.renameTo(backup)) {
                        "Failed to back up ${original.absolutePath} to ${backup.absolutePath}"
                    }
                }
                return DirectoryBackup(original = original, backup = backup, existed = existed)
            }
        }
    }

    private companion object {
        const val OPTIONAL_MOD_COUNT = 260
        const val WORKSHOP_INSTALLED_COUNT = 20
        const val LAUNCHER_DOCK_MODS_TAG = "launcher_dock_item_Mods"
        const val MODS_CONTENT_READY_TAG = "mods_content_ready"
        const val APP_FOREGROUND_TIMEOUT_MS = 10_000L
        const val LAUNCH_TIMEOUT_MS = 30_000L
        const val MODS_READY_TIMEOUT_MS = 30_000L
        const val DIALOG_DISMISS_TIMEOUT_MS = 5_000L
    }
}
