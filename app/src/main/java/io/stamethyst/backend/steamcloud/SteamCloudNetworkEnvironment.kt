package io.stamethyst.backend.steamcloud

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.Context
import io.stamethyst.config.LauncherConfig
import java.io.File

internal object SteamCloudNetworkEnvironment {
    private const val LAST_CM_ENDPOINT_FILE_NAME = "last-websocket-cm-endpoint.txt"
    private const val CM_SERVER_LIST_FILE_NAME = "steam-cm-server-list.bin"

    fun shouldPromptForDirectMode(context: Context): Boolean {
        if (isWattAccelerationEnabled(context)) {
            return true
        }
        return isVpnActive(context)
    }

    fun switchToDirectMode(context: Context) {
        LauncherConfig.setSteamCloudWattAccelerationEnabled(context, false)
        clearNetworkCache(context)
    }

    fun clearNetworkCache(context: Context) {
        lastCmEndpointFile(context).delete()
        cmServerListFile(context).delete()
    }

    fun lastCmEndpointFile(context: Context): File =
        File(SteamCloudManifestStore.outputDir(context), LAST_CM_ENDPOINT_FILE_NAME)

    fun cmServerListFile(context: Context): File =
        File(SteamCloudManifestStore.outputDir(context), CM_SERVER_LIST_FILE_NAME)

    fun readCachedCmEndpoint(context: Context): String =
        runCatching { lastCmEndpointFile(context).takeIf { it.isFile }?.readText(Charsets.UTF_8).orEmpty().trim() }
            .getOrDefault("")

    @JvmStatic
    fun isProxyOrAcceleratorActive(context: Context): Boolean =
        isWattAccelerationEnabled(context) || isVpnActive(context)

    @Suppress("DEPRECATION")
    internal fun hasVpnTransport(
        connectivityManager: ConnectivityManager?,
        capabilitiesProvider: (android.net.Network) -> NetworkCapabilities?,
    ): Boolean {
        if (connectivityManager == null) {
            return false
        }
        val activeNetwork = connectivityManager.activeNetwork
        if (activeNetwork != null &&
            capabilitiesProvider(activeNetwork)?.let { hasVpnTransport(it::hasTransport) } == true
        ) {
            return true
        }
        return connectivityManager.allNetworks.any { network ->
            capabilitiesProvider(network)?.let { hasVpnTransport(it::hasTransport) } == true
        }
    }

    internal fun hasVpnTransport(hasTransport: (Int) -> Boolean): Boolean =
        hasTransport(NetworkCapabilities.TRANSPORT_VPN)

    private fun isVpnActive(context: Context): Boolean =
        runCatching {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            hasVpnTransport(connectivityManager) { network ->
                connectivityManager?.getNetworkCapabilities(network)
            }
        }.getOrDefault(false)

    private fun isWattAccelerationEnabled(context: Context): Boolean =
        runCatching {
            LauncherConfig.isSteamCloudWattAccelerationEnabled(context)
        }.getOrDefault(false)
}
