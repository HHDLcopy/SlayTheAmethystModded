package io.stamethyst.backend.steamcloud

import android.content.Context
import io.stamethyst.backend.github.ExperimentalGithubDirectAccessRuntime
import io.stamethyst.backend.github.ExperimentalGithubDirectAccessInterceptor
import io.stamethyst.backend.github.WATT_PROXY_TYPE_DIRECT
import io.stamethyst.backend.github.WATT_PROXY_TYPE_REVERSE_PROXY
import io.stamethyst.backend.github.WattToolkitRouteProfile
import io.stamethyst.backend.github.createWattToolkitRuntime
import io.stamethyst.backend.network.NetworkAccelerationPolicy
import io.stamethyst.config.LauncherConfig
import java.io.File
import javax.net.ssl.HttpsURLConnection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

internal val SteamCommunityWattToolkitRouteProfile = WattToolkitRouteProfile(
    name = "steam-community",
    // v4 invalidates caches created before official-link candidates were persisted.
    cacheFileName = "watt-steam-community-route-cache-v4.json",
    supportedHosts = setOf("steamcommunity.com", "www.steamcommunity.com"),
    bootstrapForwardTargets = listOf("https://steamcommunity.rmbgame.net"),
)

internal val SteamStoreWattToolkitRouteProfile = WattToolkitRouteProfile(
    name = "steam-store",
    cacheFileName = "watt-steam-store-route-cache-v3.json",
    supportedHosts = setOf(
        "api.steampowered.com",
        "store.steampowered.com",
        "help.steampowered.com",
        "login.steampowered.com",
        "checkout.steampowered.com",
    ),
    bootstrapForwardTargets = listOf("steamstore.rmbgame.net"),
)

internal val SteamImageCdnWattToolkitRouteProfile = WattToolkitRouteProfile(
    name = "steam-image-cdn",
    // v3 invalidates caches created before the suffix families were declared.
    cacheFileName = "watt-steam-image-cdn-route-cache-v3.json",
    supportedHosts = setOf(
        "steamcdn-a.akamaihd.net",
        "steamuserimages-a.akamaihd.net",
        "images.steamusercontent.com",
        "steamusercontent.com",
        "cdn.akamai.steamstatic.com",
        "community.akamai.steamstatic.com",
        "avatars.akamai.steamstatic.com",
        "store.akamai.steamstatic.com",
        "avatars.fastly.steamstatic.com",
    ),
    bootstrapForwardTargets = listOf("steamimage.rmbgame.net"),
    // Upstream publishes one rule per image CDN family. Enumerating hosts exactly left
    // siblings such as avatars.steamstatic.com and avatars.cloudflare.steamstatic.com
    // unaccelerated, which is precisely where logged-in profile avatars resolve to.
    supportedHostSuffixes = setOf(
        ".steamstatic.com",
        ".akamaihd.net",
        ".steamusercontent.com",
    ),
)

internal val SteamMediaWattToolkitRouteProfile = WattToolkitRouteProfile(
    name = "steam-media",
    cacheFileName = "watt-steam-media-route-cache-v2.json",
    supportedHosts = setOf("media.steampowered.com"),
    bootstrapForwardTargets = listOf("steammedia.rmbgame.net"),
)

internal val SteamContentCdnWattToolkitRouteProfile = WattToolkitRouteProfile(
    name = "steam-content-cdn",
    cacheFileName = "watt-steam-content-cdn-route-cache-v3.json",
    supportedHosts = setOf(
        "st.dl.eccdnx.com",
        "shared.st.dl.eccdnx.com",
        "clan.st.dl.eccdnx.com",
        "store.st.dl.eccdnx.com",
        "avatars.st.dl.eccdnx.com",
        "media.st.dl.eccdnx.com",
        "video.st.dl.eccdnx.com",
        "xz.pphimalayanrt.com",
        "dl.steam.clngaa.com",
        "files.steam.nsclouds.cn",
    ),
    bootstrapForwardTargets = emptyList(),
    supportedProxyTypes = setOf(WATT_PROXY_TYPE_DIRECT, WATT_PROXY_TYPE_REVERSE_PROXY),
    // SteamPipe CDN rules are published as unchecked while their health is being updated.
    // Workshop downloads still need the available reverse-proxy route instead of falling
    // back to the origin CDN whenever that flag is false.
    allowUncheckedRoutes = true,
)

private val defaultSteamCloudWattToolkitRouteProfiles = listOf(
    SteamCommunityWattToolkitRouteProfile,
    SteamStoreWattToolkitRouteProfile,
    SteamImageCdnWattToolkitRouteProfile,
    SteamMediaWattToolkitRouteProfile,
    SteamContentCdnWattToolkitRouteProfile,
)

object SteamCloudAcceleratedHttp {
    private val runtimeCache = ConcurrentHashMap<String, ExperimentalGithubDirectAccessRuntime>()

    @JvmStatic
    fun isEnabled(context: Context): Boolean =
        NetworkAccelerationPolicy.shouldUseAcceleratedLinks(
            context = context,
            configuredEnabled = LauncherConfig.isSteamCloudWattAccelerationEnabled(context),
        )

    @JvmStatic
    @JvmOverloads
    fun createClient(
        context: Context,
        connectTimeoutMs: Long,
        readTimeoutMs: Long,
        callTimeoutMs: Long,
        enabled: Boolean = isEnabled(context),
        enabledProvider: (() -> Boolean)? = null,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)

        if (!enabled) {
            return builder.build()
        }

        val accelerationEnabledProvider = {
            NetworkAccelerationPolicy.shouldUseAcceleratedLinks(
                context = context,
                configuredEnabled = enabledProvider?.invoke() ?: enabled,
            )
        }
        val filesDir = context.filesDir
        val runtime = runtimeCache.getOrPut(filesDir.absolutePath) {
            createSteamCloudWattToolkitRuntime(filesDir)
        }
        return builder
            .hostnameVerifier(runtime.hostnameVerifier)
            .addInterceptor(
                ExperimentalGithubDirectAccessInterceptor(
                    routeResolvers = runtime.resolvers,
                    directCallFactory = runtime.directHttpClient,
                    forwardDns = runtime.forwardDns,
                    enabledProvider = accelerationEnabledProvider,
                ),
            )
            .build()
    }

    /**
     * Builds the official Steam protocol client from a shared HTTP client.
     *
     * Steam CM directory responses determine the websocket endpoint used by
     * JavaSteam and the protocol module. They must not be rewritten by Watt
     * forwarding rules intended for Steam web/CDN traffic.
     */
    @JvmStatic
    fun createProtocolClient(baseClient: OkHttpClient): OkHttpClient = baseClient.newBuilder()
        .apply {
            interceptors().clear()
            networkInterceptors().clear()
            hostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier())
        }
        .build()

    @JvmStatic
    fun clearRuntimeCacheForTests() {
        runtimeCache.clear()
    }
}

internal fun createSteamCloudWattToolkitRuntime(
    filesDir: File,
    routeProfiles: List<WattToolkitRouteProfile> = defaultSteamCloudWattToolkitRouteProfiles,
): ExperimentalGithubDirectAccessRuntime = createWattToolkitRuntime(
    filesDir = filesDir,
    cacheSubDirectory = "steam-cloud/network",
    routeProfiles = routeProfiles,
    connectTimeoutMs = STEAM_CLOUD_DIRECT_ACCESS_CONNECT_TIMEOUT_MS,
    readTimeoutMs = STEAM_CLOUD_DIRECT_ACCESS_READ_TIMEOUT_MS,
)

private const val STEAM_CLOUD_DIRECT_ACCESS_CONNECT_TIMEOUT_MS = 8_000L
private const val STEAM_CLOUD_DIRECT_ACCESS_READ_TIMEOUT_MS = 60_000L
