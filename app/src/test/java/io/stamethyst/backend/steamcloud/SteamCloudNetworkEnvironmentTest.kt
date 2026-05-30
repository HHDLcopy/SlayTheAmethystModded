package io.stamethyst.backend.steamcloud

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.NetworkCapabilities
import io.stamethyst.config.LauncherConfig
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamCloudNetworkEnvironmentTest {
    @Test
    fun shouldPromptForDirectMode_whenWattAccelerationIsEnabled() {
        val roots = TestRoots.create("steam-cloud-direct-mode-watt")
        try {
            LauncherConfig.setSteamCloudWattAccelerationEnabled(roots.context, true)

            assertTrue(SteamCloudNetworkEnvironment.shouldPromptForDirectMode(roots.context))
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun shouldPromptForDirectMode_ignoresStaleSummaryDetection() {
        val roots = TestRoots.create("steam-cloud-direct-mode-stale-summary")
        try {
            LauncherConfig.setSteamCloudWattAccelerationEnabled(roots.context, false)
            val summary = SteamCloudDiagnosticsStore.summaryFile(roots.context)
            summary.parentFile?.mkdirs()
            summary.writeText(
                """
                Steam Cloud diagnostics summary

                Proxy/Accelerator Detected: yes
                """.trimIndent(),
                Charsets.UTF_8,
            )

            assertFalse(SteamCloudNetworkEnvironment.shouldPromptForDirectMode(roots.context))
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun shouldPromptForDirectMode_ignoresPrivateCachedCmEndpoint() {
        val roots = TestRoots.create("steam-cloud-direct-mode-private-cm")
        try {
            LauncherConfig.setSteamCloudWattAccelerationEnabled(roots.context, false)
            val endpoint = SteamCloudNetworkEnvironment.lastCmEndpointFile(roots.context)
            endpoint.parentFile?.mkdirs()
            endpoint.writeText("10.0.0.5:443\n", Charsets.UTF_8)

            assertFalse(SteamCloudNetworkEnvironment.shouldPromptForDirectMode(roots.context))
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun shouldPromptForDirectMode_returnsFalseAfterDirectModeSwitch() {
        val roots = TestRoots.create("steam-cloud-direct-mode-switch")
        try {
            LauncherConfig.setSteamCloudWattAccelerationEnabled(roots.context, true)

            SteamCloudNetworkEnvironment.switchToDirectMode(roots.context)

            assertFalse(SteamCloudNetworkEnvironment.shouldPromptForDirectMode(roots.context))
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun isProxyOrAcceleratorActive_usesWattAccelerationSetting() {
        val roots = TestRoots.create("steam-cloud-proxy-active-watt")
        try {
            LauncherConfig.setSteamCloudWattAccelerationEnabled(roots.context, true)

            assertTrue(SteamCloudNetworkEnvironment.isProxyOrAcceleratorActive(roots.context))
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }

    @Test
    fun hasVpnTransport_usesSystemVpnTransportFlag() {
        assertTrue(
            SteamCloudNetworkEnvironment.hasVpnTransport { transport ->
                transport == NetworkCapabilities.TRANSPORT_VPN
            }
        )
        assertFalse(
            SteamCloudNetworkEnvironment.hasVpnTransport { transport ->
                transport == NetworkCapabilities.TRANSPORT_WIFI
            }
        )
    }

    private class TestRoots private constructor(
        val rootDir: File,
        val context: Context,
    ) {
        companion object {
            fun create(prefix: String): TestRoots {
                val rootDir = Files.createTempDirectory(prefix).toFile()
                val filesDir = File(rootDir, "internal-files").apply { mkdirs() }
                val externalFilesDir = File(rootDir, "external-files").apply { mkdirs() }
                val prefs = LinkedHashMap<String, InMemorySharedPreferences>()
                return TestRoots(
                    rootDir = rootDir,
                    context = object : ContextWrapper(Application()) {
                        override fun getFilesDir(): File = filesDir

                        override fun getExternalFilesDir(type: String?): File = externalFilesDir

                        override fun getPackageName(): String = "io.stamethyst.test"

                        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                            prefs.getOrPut(name) { InMemorySharedPreferences() }
                    },
                )
            }
        }
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val values = LinkedHashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = synchronized(values) { LinkedHashMap(values) }

        override fun getString(key: String, defValue: String?): String? =
            synchronized(values) { values[key] as? String ?: defValue }

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            synchronized(values) { (values[key] as? Set<String>)?.toMutableSet() ?: defValues }

        override fun getInt(key: String, defValue: Int): Int =
            synchronized(values) { values[key] as? Int ?: defValue }

        override fun getLong(key: String, defValue: Long): Long =
            synchronized(values) { values[key] as? Long ?: defValue }

        override fun getFloat(key: String, defValue: Float): Float =
            synchronized(values) { values[key] as? Float ?: defValue }

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            synchronized(values) { values[key] as? Boolean ?: defValue }

        override fun contains(key: String): Boolean = synchronized(values) { values.containsKey(key) }

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private val removals = LinkedHashSet<String>()
            private var clear = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor =
                apply { pending[key] = value }

            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
                apply { pending[key] = values?.toMutableSet() }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor =
                apply { pending[key] = value }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor =
                apply { pending[key] = value }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
                apply { pending[key] = value }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
                apply { pending[key] = value }

            override fun remove(key: String): SharedPreferences.Editor = apply { removals += key }

            override fun clear(): SharedPreferences.Editor = apply { clear = true }

            override fun commit(): Boolean {
                synchronized(values) {
                    if (clear) {
                        values.clear()
                    }
                    removals.forEach(values::remove)
                    pending.forEach { (key, value) ->
                        if (value == null) {
                            values.remove(key)
                        } else {
                            values[key] = value
                        }
                    }
                }
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
