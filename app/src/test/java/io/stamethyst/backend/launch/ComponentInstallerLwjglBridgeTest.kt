package io.stamethyst.backend.launch

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentInstallerLwjglBridgeTest {
    @Test
    fun lwjglBridgeVersionCheck_rejectsStalePersistentBridge() {
        val root = Files.createTempDirectory("lwjgl-bridge-version-").toFile()
        try {
            val versionFile = writeFile(root, "version", "old-contract")
            val jarFile = writeFile(root, "lwjgl-glfw-classes.jar", "old bridge")

            assertFalse(
                ComponentInstaller.isLwjglBridgeVersionCurrent(
                    expectedVersion = "new-contract",
                    installedVersionFile = versionFile,
                    installedJar = jarFile
                )
            )

            versionFile.writeText("new-contract", StandardCharsets.UTF_8)

            assertTrue(
                ComponentInstaller.isLwjglBridgeVersionCurrent(
                    expectedVersion = "new-contract",
                    installedVersionFile = versionFile,
                    installedJar = jarFile
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun lwjglBridgeVersionCheck_requiresNonEmptyBridgeJarAndVersion() {
        val root = Files.createTempDirectory("lwjgl-bridge-version-missing-").toFile()
        try {
            val versionFile = writeFile(root, "version", "current-contract")
            val jarFile = File(root, "lwjgl-glfw-classes.jar")
            jarFile.createNewFile()

            assertFalse(
                ComponentInstaller.isLwjglBridgeVersionCurrent(
                    expectedVersion = "current-contract",
                    installedVersionFile = versionFile,
                    installedJar = jarFile
                )
            )
            assertFalse(
                ComponentInstaller.isLwjglBridgeVersionCurrent(
                    expectedVersion = null,
                    installedVersionFile = versionFile,
                    installedJar = writeFile(root, "valid.jar", "bridge")
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeFile(root: File, name: String, contents: String): File {
        return File(root, name).also { file ->
            file.writeText(contents, StandardCharsets.UTF_8)
        }
    }
}
