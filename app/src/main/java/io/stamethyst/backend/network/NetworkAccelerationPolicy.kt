package io.stamethyst.backend.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

object NetworkAccelerationPolicy {
    @JvmStatic
    fun shouldUseAcceleratedLinks(
        context: Context,
        configuredEnabled: Boolean,
    ): Boolean = shouldUseAcceleratedLinks(
        configuredEnabled = configuredEnabled,
        vpnActiveProvider = { isVpnActive(context) },
    )

    @JvmStatic
    fun shouldBypassAcceleratedLinks(context: Context): Boolean = isVpnActive(context)

    @JvmStatic
    fun isVpnActive(context: Context): Boolean =
        runCatching {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            hasVpnTransport(connectivityManager) { network ->
                connectivityManager?.getNetworkCapabilities(network)
            }
        }.getOrDefault(false)

    internal fun shouldUseAcceleratedLinks(
        configuredEnabled: Boolean,
        vpnActiveProvider: () -> Boolean,
    ): Boolean = configuredEnabled && !vpnActiveProvider()

    @Suppress("DEPRECATION")
    internal fun hasVpnTransport(
        connectivityManager: ConnectivityManager?,
        capabilitiesProvider: (Network) -> NetworkCapabilities?,
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
}
