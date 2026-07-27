package io.stamethyst.backend.github

import android.content.Context
import io.stamethyst.backend.network.NetworkAccelerationPolicy
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Proxy
import java.net.InetAddress
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

internal data class WattToolkitRouteProfile(
    val name: String,
    val cacheFileName: String,
    val supportedHosts: Set<String>,
    val bootstrapForwardTargets: List<String>,
    val supportedProxyTypes: Set<Int> = setOf(WATT_PROXY_TYPE_DIRECT),
    val allowUncheckedRoutes: Boolean = false,
    /**
     * Subdomain suffixes (".example.com") accelerated by the same upstream rule.
     *
     * Watt publishes one forwarding rule per logical domain family, so every
     * subdomain of that family can reuse the resolved hop. Enumerating hosts
     * exactly used to leave siblings such as avatars.githubusercontent.com
     * unaccelerated even though a working route already existed.
     */
    val supportedHostSuffixes: Set<String> = emptySet(),
)

internal val GithubApiWattToolkitRouteProfile = WattToolkitRouteProfile(
    name = "github-api",
    cacheFileName = "watt-github-api-route-cache.json",
    supportedHosts = setOf("api.github.com"),
    bootstrapForwardTargets = listOf("githubapi.rmbgame.net"),
)

internal val GithubWebWattToolkitRouteProfile = WattToolkitRouteProfile(
    name = "github-web",
    cacheFileName = "watt-github-web-route-cache.json",
    supportedHosts = setOf("github.com"),
    bootstrapForwardTargets = emptyList(),
)

internal val GithubUserContentWattToolkitRouteProfile = WattToolkitRouteProfile(
    name = "githubusercontent",
    cacheFileName = "watt-githubusercontent-route-cache.json",
    supportedHosts = setOf(
        "codeload.github.com",
        "githubusercontent.com",
        "media.githubusercontent.com",
        "objects.githubusercontent.com",
        "raw.github.com",
        "raw.githubusercontent.com",
        "release-assets.githubusercontent.com",
    ),
    bootstrapForwardTargets = emptyList(),
    // Upstream publishes one githubusercontent.com rule; avatars/user-images and
    // other siblings previously fell through to an unaccelerated direct request.
    supportedHostSuffixes = setOf(".githubusercontent.com"),
)

private val defaultExperimentalGithubDirectAccessProfiles = listOf(
    GithubApiWattToolkitRouteProfile,
    GithubWebWattToolkitRouteProfile,
    GithubUserContentWattToolkitRouteProfile,
)

internal data class ExperimentalGithubDirectAccessRuntime(
    val resolvers: List<WattToolkitGithubRouteResolver>,
    val hostnameVerifier: HostnameVerifier,
    val directHttpClient: OkHttpClient,
    val forwardDns: WattToolkitForwardDns? = null,
)

internal object GithubAcceleratedHttp {
    private val runtimeCache = ConcurrentHashMap<String, ExperimentalGithubDirectAccessRuntime>()

    fun createClientPair(
        context: Context,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        followRedirects: Boolean = true,
    ): GithubRequestClients {
        val accelerationAllowedProvider = {
            NetworkAccelerationPolicy.shouldUseAcceleratedLinks(
                context = context,
                configuredEnabled = true,
            )
        }
        return GithubRequestClients(
            plainClient = createPlainClient(
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs,
                followRedirects = followRedirects,
            ),
            acceleratedClient = createClient(
                context = context,
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs,
                followRedirects = followRedirects,
            ),
            accelerationAllowedProvider = accelerationAllowedProvider,
        )
    }

    fun createClient(
        context: Context,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        followRedirects: Boolean = true,
    ): OkHttpClient {
        val filesDir = context.filesDir
        val runtime = runtimeCache.getOrPut(filesDir.absolutePath) {
            createExperimentalGithubDirectAccessRuntime(filesDir)
        }
        return createPlainClient(
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
            followRedirects = followRedirects,
        ).newBuilder()
            .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)
            .hostnameVerifier(runtime.hostnameVerifier)
            .addExperimentalGithubDirectAccess(
                runtime = runtime,
                enabledProvider = {
                    NetworkAccelerationPolicy.shouldUseAcceleratedLinks(
                        context = context,
                        configuredEnabled = true,
                    )
                },
            )
            .build()
    }

    internal fun clearRuntimeCacheForTests() {
        runtimeCache.clear()
    }
}

internal data class GithubRequestClients(
    val plainClient: OkHttpClient,
    val acceleratedClient: OkHttpClient,
    val accelerationAllowedProvider: () -> Boolean = { true },
) {
    fun pick(useAcceleration: Boolean): OkHttpClient {
        return if (useAcceleration && accelerationAllowedProvider()) acceleratedClient else plainClient
    }
}

internal fun createPlainClient(
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
    followRedirects: Boolean = true,
): OkHttpClient {
    return OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .writeTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .followRedirects(followRedirects)
        .followSslRedirects(followRedirects)
        .build()
}

internal fun createExperimentalGithubDirectAccessRuntime(
    filesDir: File,
    routeProfiles: List<WattToolkitRouteProfile> = defaultExperimentalGithubDirectAccessProfiles,
): ExperimentalGithubDirectAccessRuntime = createWattToolkitRuntime(
    filesDir = filesDir,
    cacheSubDirectory = "github/network",
    routeProfiles = routeProfiles,
    connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS,
    readTimeoutMs = DEFAULT_READ_TIMEOUT_MS,
)

/**
 * Single builder for every Watt forwarding runtime.
 *
 * GitHub and Steam previously kept near-identical copies of this wiring, which
 * is how the certificate-validation fix could be applied to one and missed by
 * the other. Both now share this code path.
 */
internal fun createWattToolkitRuntime(
    filesDir: File,
    cacheSubDirectory: String,
    routeProfiles: List<WattToolkitRouteProfile>,
    connectTimeoutMs: Long,
    readTimeoutMs: Long,
): ExperimentalGithubDirectAccessRuntime {
    val forwardDns = WattToolkitForwardDns()
    val resolvers = routeProfiles.map { routeProfile ->
        WattToolkitGithubRouteResolver(
            routeProfile = routeProfile,
            routeStore = FileBackedWattToolkitGithubRouteStore(
                file = File(filesDir, "$cacheSubDirectory/${routeProfile.cacheFileName}"),
                fallbackLogicalHosts = routeProfile.supportedHosts,
                fallbackLogicalHostSuffixes = routeProfile.supportedHostSuffixes,
            ),
        )
    }
    val unsafeHostProvider: (String) -> Boolean = { host ->
        resolvers.any { resolver -> resolver.allowsUnsafeHostnameBypass(host) }
    }
    val hostnameVerifier = GithubDirectHostnameVerifier(unsafeHostBypassProvider = unsafeHostProvider)
    val directHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .hostnameVerifier(hostnameVerifier)
        .dns(forwardDns)
        .trustWattToolkitForwardCertificates(unsafeHostProvider)
        .followRedirects(false)
        .followSslRedirects(false)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()
    return ExperimentalGithubDirectAccessRuntime(
        resolvers = resolvers,
        hostnameVerifier = hostnameVerifier,
        directHttpClient = directHttpClient,
        forwardDns = forwardDns,
    )
}

internal fun OkHttpClient.Builder.addExperimentalGithubDirectAccess(
    runtime: ExperimentalGithubDirectAccessRuntime,
    enabledProvider: () -> Boolean = { true },
): OkHttpClient.Builder = apply {
    addInterceptor(
        ExperimentalGithubDirectAccessInterceptor(
            routeResolvers = runtime.resolvers,
            directCallFactory = runtime.directHttpClient,
            forwardDns = runtime.forwardDns,
            enabledProvider = enabledProvider,
        ),
    )
}

/**
 * Installs a trust manager that only relaxes chain validation for hops whose
 * upstream rule explicitly sets IgnoreSSLCertVerification.
 *
 * Previously every accelerated request accepted any certificate, so a mirror
 * operator or on-path attacker could read traffic including Steam session
 * cookies. Most published routes (Steam store/community, for example) declare
 * IgnoreSSLCertVerification=false and rely on SNI cloaking instead, so they
 * still validate normally through the platform trust manager.
 */
internal fun OkHttpClient.Builder.trustWattToolkitForwardCertificates(
    unsafeHostProvider: (String) -> Boolean = { false },
): OkHttpClient.Builder = apply {
    val platformTrustManager = platformX509TrustManager() ?: return@apply
    val trustManager = WattToolkitForwardTrustManager(
        delegate = platformTrustManager,
        unsafeHostProvider = unsafeHostProvider,
    )
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustManager), null)
    }
    sslSocketFactory(sslContext.socketFactory, trustManager)
}

private fun platformX509TrustManager(): X509TrustManager? = runCatching {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as java.security.KeyStore?)
    factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
}.getOrNull()

internal class ExperimentalGithubDirectAccessInterceptor(
    private val routeResolvers: List<WattToolkitGithubRouteResolver>,
    private val directCallFactory: okhttp3.Call.Factory,
    private val maxRedirects: Int = MAX_FOLLOW_UPS,
    private val enabledProvider: () -> Boolean = { true },
    private val forwardDns: WattToolkitForwardDns? = null,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!enabledProvider()) {
            return chain.proceed(request)
        }
        if (routeResolvers.none { resolver -> resolver.supports(request.url.host) }) {
            return chain.proceed(request)
        }
        return try {
            executeDirectAccessRequest(request)
        } catch (_: IOException) {
            chain.proceed(request)
        }
    }

    private fun executeDirectAccessRequest(
        initialLogicalRequest: Request,
    ): Response {
        var logicalRequest = initialLogicalRequest
        var followUpCount = 0
        var routeRefreshAttempted = false
        val failedForwardTargets = LinkedHashSet<String>()
        while (true) {
            val resolver = routeResolvers.firstOrNull { candidate -> candidate.supports(logicalRequest.url.host) }
            val route = resolver?.resolveRouteForHost(logicalRequest.url.host)
            var effectiveRoute = route
            var usedForwardTarget: String? = null
            val response = try {
                val executed = executeWithForwardTargetFallback(
                    logicalRequest = logicalRequest,
                    route = route,
                    failedForwardTargets = failedForwardTargets,
                )
                usedForwardTarget = executed.usedForwardTarget
                executed.response
            } catch (error: IOException) {
                val refreshedRoute = resolver?.refreshRouteForHost(
                    host = logicalRequest.url.host,
                    excludedForwardTargets = failedForwardTargets,
                ) ?: throw error
                effectiveRoute = refreshedRoute
                try {
                    val executed = executeWithForwardTargetFallback(
                        logicalRequest = logicalRequest,
                        route = refreshedRoute,
                        failedForwardTargets = failedForwardTargets,
                    )
                    usedForwardTarget = executed.usedForwardTarget
                    executed.response
                } catch (refreshedError: IOException) {
                    refreshedError.addSuppressed(error)
                    throw refreshedError
                }
            }
            if (
                resolver != null &&
                effectiveRoute != null &&
                usedForwardTarget != null &&
                !routeRefreshAttempted &&
                response.code in STALE_ROUTE_RESPONSE_CODES
            ) {
                routeRefreshAttempted = true
                failedForwardTargets += usedForwardTarget
                response.close()
                resolver.refreshRouteForHost(
                    host = logicalRequest.url.host,
                    excludedForwardTargets = failedForwardTargets,
                )
                continue
            }
            if (
                resolver != null &&
                effectiveRoute != null &&
                usedForwardTarget != null &&
                response.isSuccessful
            ) {
                // Real successful hop becomes the preferred best path for next cold start.
                resolver.confirmSuccessfulForwardTarget(
                    host = logicalRequest.url.host,
                    successfulTarget = usedForwardTarget,
                )
            }
            val redirectTarget = response.redirectTarget(logicalRequest.url, effectiveRoute)
            if (redirectTarget == null) {
                return response.newBuilder()
                    .request(logicalRequest)
                    .build()
            }
            if (followUpCount >= maxRedirects) {
                response.close()
                throw ProtocolException("Too many GitHub direct-access redirects: $maxRedirects")
            }
            val nextLogicalRequest = buildRedirectRequest(
                previousLogicalRequest = logicalRequest,
                redirectUrl = redirectTarget,
                responseCode = response.code,
            )
            response.close()
            logicalRequest = nextLogicalRequest
            followUpCount++
        }
    }

    private data class ForwardedExecution(
        val response: Response,
        val usedForwardTarget: String?,
    )

    private fun executeWithForwardTargetFallback(
        logicalRequest: Request,
        route: WattToolkitGithubRoute?,
        failedForwardTargets: MutableSet<String>,
    ): ForwardedExecution {
        val candidateRoutes = route?.forwardTargetCandidates() ?: listOf(null)
        var lastError: IOException? = null
        candidateRoutes.forEach { candidateRoute ->
            val candidateTarget = candidateRoute?.forwardTargets?.firstOrNull()
            if (
                candidateTarget != null &&
                failedForwardTargets.any { failed -> forwardTargetsEquivalent(failed, candidateTarget) }
            ) {
                return@forEach
            }
            try {
                val response = directCallFactory.newCall(buildNetworkRequest(logicalRequest, candidateRoute)).execute()
                return ForwardedExecution(
                    response = response,
                    usedForwardTarget = candidateTarget,
                )
            } catch (error: IOException) {
                if (candidateTarget != null) {
                    failedForwardTargets += candidateTarget
                }
                lastError = error
            }
        }
        throw lastError ?: IOException("No Steam acceleration route candidate was available")
    }

    private fun forwardTargetsEquivalent(left: String, right: String): Boolean {
        val leftNormalized = left.trim()
        val rightNormalized = right.trim()
        if (leftNormalized.equals(rightNormalized, ignoreCase = true)) {
            return true
        }
        val leftHost = extractForwardHost(leftNormalized)
        val rightHost = extractForwardHost(rightNormalized)
        return leftHost != null && leftHost == rightHost
    }

    private fun buildNetworkRequest(
        logicalRequest: Request,
        route: WattToolkitGithubRoute?,
    ): Request {
        if (route == null) {
            return logicalRequest
        }
        val logicalUrl = route.normalizeLogicalUrl(
            url = logicalRequest.url,
            fallbackLogicalHost = logicalRequest.url.host,
        )
        val shouldForward = route.matchesLogicalHost(logicalUrl.host)
        val networkUrl = if (shouldForward) route.buildForwardedUrl(logicalUrl) else logicalUrl
        if (shouldForward) {
            forwardDns?.register(route)
        }
        return logicalRequest.newBuilder()
            .url(networkUrl)
            .apply {
                if (shouldForward) {
                    header("Host", logicalUrl.host)
                } else {
                    removeHeader("Host")
                }
            }
            .build()
    }

    private fun buildRedirectRequest(
        previousLogicalRequest: Request,
        redirectUrl: HttpUrl,
        responseCode: Int,
    ): Request {
        val preserveBody = responseCode == HTTP_TEMP_REDIRECT || responseCode == HTTP_PERM_REDIRECT
        val originalMethod = previousLogicalRequest.method
        val redirectMethod = when {
            preserveBody -> originalMethod
            originalMethod == HTTP_METHOD_GET || originalMethod == HTTP_METHOD_HEAD -> originalMethod
            else -> HTTP_METHOD_GET
        }
        val redirectBody: RequestBody? = if (redirectMethod == originalMethod) previousLogicalRequest.body else null
        return previousLogicalRequest.newBuilder()
            .url(redirectUrl)
            .method(redirectMethod, redirectBody)
            .apply {
                if (redirectBody == null) {
                    removeHeader("Transfer-Encoding")
                    removeHeader("Content-Length")
                    removeHeader("Content-Type")
                }
            }
            .build()
    }
}

internal class GithubDirectHostnameVerifier(
    private val defaultVerifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier(),
    private val unsafeHostBypassProvider: (String) -> Boolean,
) : HostnameVerifier {
    override fun verify(hostname: String, session: SSLSession): Boolean {
        return defaultVerifier.verify(hostname, session) || unsafeHostBypassProvider(hostname)
    }
}

internal class WattToolkitGithubRouteResolver(
    private val routeProfile: WattToolkitRouteProfile = GithubApiWattToolkitRouteProfile,
    private val client: OkHttpClient = defaultWattToolkitRouteClient(),
    private val projectGroupsUrl: HttpUrl = WATT_ACCELERATOR_PROJECTGROUPS_URL.toHttpUrl(),
    private val routeStore: WattToolkitGithubRouteStore = NoOpWattToolkitGithubRouteStore,
    private val bootstrapRouteProvider: (WattToolkitRouteProfile) -> WattToolkitGithubRoute? = ::defaultBootstrapRouteForProfile,
    private val forwardTargetProbe: (String) -> WattToolkitForwardTargetProbe = ::probeWattToolkitForwardTarget,
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val sleepProvider: (Long) -> Unit = { delayMs -> Thread.sleep(delayMs) },
    private val backgroundExecutor: Executor = sharedBestPathBackgroundExecutor,
) {
    private val lock = Any()
    private val normalizedSupportedHosts = routeProfile.supportedHosts.map { it.lowercase(Locale.ROOT) }.toSet()
    private val normalizedSupportedHostSuffixes = routeProfile.supportedHostSuffixes
        .map { it.lowercase(Locale.ROOT) }
        .map { if (it.startsWith(".")) it else ".$it" }
        .toSet()
    private val backgroundRefreshInFlight = AtomicBoolean(false)
    private val recentlyFailedForwardTargets = LinkedHashMap<String, Long>()

    @Volatile
    private var cachedRoute: WattToolkitGithubRoute? = null

    @Volatile
    private var cachedAtMs: Long = 0L

    @Volatile
    private var lastBackgroundSearchAtMs: Long = 0L

    @Volatile
    private var lastHttpConfirmedAtMs: Long = 0L

    @Volatile
    private var lastHttpConfirmedTarget: String = ""

    @Volatile
    private var persistedRouteLoaded: Boolean = false

    fun supports(host: String): Boolean = isProfileHost(host)

    /**
     * Exact host match, or a subdomain of a declared suffix family.
     *
     * Watt forwards a whole domain family through one rule, so restricting the
     * resolver to a hand-written host list silently dropped siblings that the
     * upstream rule already covers.
     */
    private fun isProfileHost(host: String): Boolean {
        val normalizedHost = host.lowercase(Locale.ROOT)
        if (normalizedHost in normalizedSupportedHosts) {
            return true
        }
        return normalizedSupportedHostSuffixes.any { suffix -> normalizedHost.endsWith(suffix) }
    }

    fun allowsUnsafeHostnameBypass(host: String): Boolean =
        cachedRoute?.shouldBypassHostnameVerification(host) == true

    fun resolveRouteForHost(host: String): WattToolkitGithubRoute? {
        val normalizedHost = host.lowercase(Locale.ROOT)
        if (!isProfileHost(normalizedHost)) {
            return null
        }
        val now = nowProvider()
        val cachedMatch = synchronized(lock) {
            restorePersistedRouteLocked()
            pruneFailedTargetsLocked(now)
            val cached = cachedRoute?.takeIf { it.matchesLogicalHost(normalizedHost) } ?: return@synchronized null
            val preferred = cached.forwardTargets.firstOrNull().orEmpty()
            if (
                preferred.isNotEmpty() &&
                recentlyFailedForwardTargets.keys.any { failed ->
                    forwardTargetsEquivalent(failed, preferred)
                }
            ) {
                // Last preferred hop already failed real traffic; rediscover instead of
                // replaying a known-bad best-path cache.
                null
            } else {
                cached
            }
        }
        if (cachedMatch != null) {
            // Always prefer last confirmed best path on the hot path.
            // Stale caches still serve immediately; discovery continues in background.
            scheduleBackgroundBestPathSearch(normalizedHost, force = now - cachedAtMs >= ROUTE_CACHE_TTL_MS)
            return cachedMatch
        }

        val excluded = synchronized(lock) {
            pruneFailedTargetsLocked(now)
            recentlyFailedForwardTargets.keys.toSet()
        }
        val discovered = discoverBestRoute(normalizedHost, excluded) ?: return null
        synchronized(lock) {
            installRouteLocked(discovered, now, httpConfirmed = false)
            return discovered
        }
    }

    fun refreshRouteForHost(
        host: String,
        excludedForwardTargets: Collection<String> = emptyList(),
    ): WattToolkitGithubRoute? {
        val normalizedHost = host.lowercase(Locale.ROOT)
        if (!isProfileHost(normalizedHost)) {
            return null
        }
        val excluded = excludedForwardTargets.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        synchronized(lock) {
            markFailedTargetsLocked(excluded)
            cachedRoute = null
            cachedAtMs = 0L
            persistedRouteLoaded = true
            routeStore.clear()
        }
        val discovered = discoverBestRoute(normalizedHost, excluded) ?: return null
        synchronized(lock) {
            installRouteLocked(discovered, nowProvider(), httpConfirmed = false)
            return discovered
        }
    }

    /**
     * Pins a forward target that just completed a real request successfully as the
     * preferred best path, then keeps searching for a better path in the background.
     */
    fun confirmSuccessfulForwardTarget(host: String, successfulTarget: String) {
        val normalizedHost = host.lowercase(Locale.ROOT)
        val normalizedTarget = successfulTarget.trim()
        if (!isProfileHost(normalizedHost) || normalizedTarget.isEmpty()) {
            return
        }
        synchronized(lock) {
            restorePersistedRouteLocked()
            // A hop that just served real traffic is no longer considered failed.
            recentlyFailedForwardTargets.keys
                .filter { failed -> forwardTargetsEquivalent(failed, normalizedTarget) }
                .forEach(recentlyFailedForwardTargets::remove)
            val current = cachedRoute?.takeIf { it.matchesLogicalHost(normalizedHost) } ?: return
            val preferred = preferForwardTarget(current, normalizedTarget)
            installRouteLocked(preferred, nowProvider(), httpConfirmed = true)
        }
        scheduleBackgroundBestPathSearch(normalizedHost, force = false)
    }

    fun scheduleBackgroundBestPathSearch(host: String, force: Boolean = false) {
        val normalizedHost = host.lowercase(Locale.ROOT)
        if (!isProfileHost(normalizedHost)) {
            return
        }
        val now = nowProvider()
        if (!force) {
            val lastSearch = lastBackgroundSearchAtMs
            if (lastSearch > 0L && now - lastSearch < BACKGROUND_BEST_PATH_MIN_INTERVAL_MS) {
                return
            }
        }
        if (!backgroundRefreshInFlight.compareAndSet(false, true)) {
            return
        }
        backgroundExecutor.execute {
            try {
                val excluded = synchronized(lock) {
                    pruneFailedTargetsLocked(nowProvider())
                    recentlyFailedForwardTargets.keys.toSet()
                }
                val discovered = discoverBestRoute(normalizedHost, excluded) ?: return@execute
                synchronized(lock) {
                    val current = cachedRoute
                    if (current == null || shouldSilentSwitchTo(current, discovered)) {
                        installRouteLocked(discovered, nowProvider(), httpConfirmed = false)
                    } else {
                        lastBackgroundSearchAtMs = nowProvider()
                    }
                }
            } finally {
                backgroundRefreshInFlight.set(false)
            }
        }
    }

    private fun discoverBestRoute(
        normalizedHost: String,
        excludedForwardTargets: Set<String> = emptySet(),
    ): WattToolkitGithubRoute? {
        val fetched = runCatching(::fetchSupportedRouteWithRetries)
            .getOrNull()
            ?.takeIf { it.matchesLogicalHost(normalizedHost) }
        val bootstrap = bootstrapRouteProvider(routeProfile)
            ?.takeIf { it.matchesLogicalHost(normalizedHost) }
        val merged = mergeDiscoveredRoutes(fetched, bootstrap)
            ?.withoutExcludedForwardTargets(excludedForwardTargets)
        return merged
    }

    private fun mergeDiscoveredRoutes(
        fetched: WattToolkitGithubRoute?,
        bootstrap: WattToolkitGithubRoute?,
    ): WattToolkitGithubRoute? {
        if (fetched == null) {
            return bootstrap
        }
        if (bootstrap == null) {
            return fetched
        }
        val mergedTargets = LinkedHashSet<String>()
        // Keep probe-ranked Watt order, then append bootstrap hops as fallback.
        fetched.forwardTargets.forEach { mergedTargets += it }
        bootstrap.forwardTargets.forEach { target ->
            if (mergedTargets.none { existing -> forwardTargetsEquivalent(existing, target) }) {
                mergedTargets += target
            }
        }
        return fetched.copy(
            forwardTargets = mergedTargets.toList(),
            ignoreSslCertVerification = fetched.ignoreSslCertVerification ||
                bootstrap.ignoreSslCertVerification,
            fakeServerName = fetched.fakeServerName.ifBlank { bootstrap.fakeServerName },
        )
    }

    private fun installRouteLocked(
        route: WattToolkitGithubRoute,
        now: Long,
        httpConfirmed: Boolean,
    ) {
        cachedRoute = route
        cachedAtMs = now
        lastBackgroundSearchAtMs = now
        if (!httpConfirmed) {
            // Discovered/bootstrap routes are only in-memory until a real request
            // proves the hop. Persisting TCP-ranked Watt hits poisoned cold starts
            // with hosts like www.valvesoftware.com that never serve workshop HTML.
            return
        }
        lastHttpConfirmedAtMs = now
        lastHttpConfirmedTarget = route.forwardTargets.firstOrNull().orEmpty()
        routeStore.save(
            PersistedWattToolkitGithubRoute(
                route = route,
                cachedAtMs = now,
            ),
        )
    }

    private fun restorePersistedRouteLocked() {
        if (persistedRouteLoaded) {
            return
        }
        persistedRouteLoaded = true
        val persisted = routeStore.load() ?: return
        cachedRoute = persisted.route
        cachedAtMs = persisted.cachedAtMs
        // Treat restored cache as recently searched so cold start does not immediately
        // re-hit projectgroups; TTL/force still triggers background revalidation.
        lastBackgroundSearchAtMs = persisted.cachedAtMs
        // Restored disk cache is unproven until a real HTTP success confirms it.
        lastHttpConfirmedAtMs = 0L
        lastHttpConfirmedTarget = ""
    }

    private fun markFailedTargetsLocked(targets: Collection<String>) {
        if (targets.isEmpty()) {
            return
        }
        val now = nowProvider()
        targets.forEach { target ->
            val normalized = target.trim()
            if (normalized.isNotEmpty()) {
                recentlyFailedForwardTargets[normalized] = now
            }
        }
        pruneFailedTargetsLocked(now)
    }

    private fun pruneFailedTargetsLocked(now: Long) {
        val iterator = recentlyFailedForwardTargets.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value >= FAILED_FORWARD_TARGET_TTL_MS) {
                iterator.remove()
            }
        }
    }

    private fun preferForwardTarget(
        route: WattToolkitGithubRoute,
        successfulTarget: String,
    ): WattToolkitGithubRoute {
        val matchIndex = route.forwardTargets.indexOfFirst { target ->
            forwardTargetsEquivalent(target, successfulTarget)
        }
        if (matchIndex <= 0) {
            return route
        }
        val reordered = ArrayList<String>(route.forwardTargets.size)
        reordered += route.forwardTargets[matchIndex]
        route.forwardTargets.forEachIndexed { index, target ->
            if (index != matchIndex) {
                reordered += target
            }
        }
        return route.copy(forwardTargets = reordered)
    }

    private fun shouldSilentSwitchTo(
        current: WattToolkitGithubRoute,
        candidate: WattToolkitGithubRoute,
    ): Boolean {
        if (current.forwardTargets == candidate.forwardTargets &&
            current.ignoreSslCertVerification == candidate.ignoreSslCertVerification &&
            current.fakeServerName == candidate.fakeServerName &&
            current.logicalHosts == candidate.logicalHosts
        ) {
            return false
        }
        val currentPreferred = current.forwardTargets.firstOrNull().orEmpty()
        val candidatePreferred = candidate.forwardTargets.firstOrNull().orEmpty()
        if (currentPreferred.isEmpty()) {
            return candidatePreferred.isNotEmpty()
        }
        if (candidatePreferred.isEmpty()) {
            return false
        }
        // Never silent-switch back onto a hop that just failed real traffic.
        if (recentlyFailedForwardTargets.keys.any { failed ->
                forwardTargetsEquivalent(failed, candidatePreferred)
            }
        ) {
            return false
        }
        if (forwardTargetsEquivalent(currentPreferred, candidatePreferred)) {
            // Same best hop; still adopt refreshed host/metadata set.
            return current.forwardTargets != candidate.forwardTargets ||
                current.logicalHosts != candidate.logicalHosts ||
                current.ignoreSslCertVerification != candidate.ignoreSslCertVerification ||
                current.fakeServerName != candidate.fakeServerName
        }
        // TCP connect probes alone can rank unrelated open hosts highly.
        // Protect a recently HTTP-confirmed best path from silent demotion.
        val now = nowProvider()
        if (
            lastHttpConfirmedAtMs > 0L &&
            now - lastHttpConfirmedAtMs < ROUTE_CACHE_TTL_MS &&
            lastHttpConfirmedTarget.isNotEmpty() &&
            forwardTargetsEquivalent(currentPreferred, lastHttpConfirmedTarget)
        ) {
            return false
        }
        val currentProbe = runCatching { forwardTargetProbe(currentPreferred) }
            .getOrDefault(WattToolkitForwardTargetProbe.failed())
        val candidateProbe = runCatching { forwardTargetProbe(candidatePreferred) }
            .getOrDefault(WattToolkitForwardTargetProbe.failed())
        return when {
            candidateProbe.successRate > currentProbe.successRate -> true
            candidateProbe.successRate < currentProbe.successRate -> false
            else -> {
                val currentLatency = currentProbe.latencyMs ?: Long.MAX_VALUE
                val candidateLatency = candidateProbe.latencyMs ?: Long.MAX_VALUE
                candidateLatency + SILENT_SWITCH_LATENCY_MARGIN_MS < currentLatency
            }
        }
    }

    private fun forwardTargetsEquivalent(left: String, right: String): Boolean {
        val leftNormalized = left.trim()
        val rightNormalized = right.trim()
        if (leftNormalized.equals(rightNormalized, ignoreCase = true)) {
            return true
        }
        val leftHost = extractForwardHost(leftNormalized)
        val rightHost = extractForwardHost(rightNormalized)
        return leftHost != null && leftHost == rightHost
    }

    private fun WattToolkitGithubRoute.withoutExcludedForwardTargets(
        excludedForwardTargets: Set<String>,
    ): WattToolkitGithubRoute? {
        if (excludedForwardTargets.isEmpty()) {
            return this
        }
        val filteredTargets = forwardTargets.filterNot { target ->
            excludedForwardTargets.any { excluded -> forwardTargetsEquivalent(excluded, target) }
        }
        if (filteredTargets.isEmpty()) {
            return null
        }
        if (filteredTargets == forwardTargets) {
            return this
        }
        return copy(forwardTargets = filteredTargets)
    }

    private fun fetchSupportedRouteWithRetries(): WattToolkitGithubRoute {
        var lastError: Throwable? = null
        repeat(ROUTE_FETCH_ATTEMPTS) { attempt ->
            try {
                return fetchSupportedRoute()
            } catch (error: Throwable) {
                lastError = error
                val isLastAttempt = attempt == ROUTE_FETCH_ATTEMPTS - 1
                if (!error.isRetryableWattRouteFetchFailure() || isLastAttempt) {
                    throw error
                }
                val retryDelayMs = ROUTE_FETCH_RETRY_DELAYS_MS.getOrElse(attempt) { 0L }
                sleepForRetry(retryDelayMs)
            }
        }
        throw lastError ?: IllegalStateException("Watt Toolkit route fetch failed without exception")
    }

    private fun sleepForRetry(delayMs: Long) {
        if (delayMs <= 0L) {
            return
        }
        try {
            sleepProvider(delayMs)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting to retry Watt route fetch", error)
        }
    }

    private fun fetchSupportedRoute(): WattToolkitGithubRoute {
        val request = Request.Builder()
            .url(projectGroupsUrl)
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Watt Toolkit route request failed: ${response.code}")
            }
            val payload = response.body.string()
            val matchedProject = findMatchingProject(payload)
                ?: throw IOException(
                    "Watt Toolkit route was not found for hosts=${normalizedSupportedHosts.joinToString(";")}",
                )
            if (matchedProject.proxyType !in routeProfile.supportedProxyTypes) {
                throw IOException(
                    "Unsupported Watt Toolkit route type for hosts=${matchedProject.logicalHosts.joinToString(";")}: ${matchedProject.proxyType}",
                )
            }
            return WattToolkitGithubRoute(
                logicalHosts = matchedProject.logicalHosts,
                forwardTargets = rankForwardTargets(matchedProject.forwardTargets),
                ignoreSslCertVerification = matchedProject.ignoreSslCertVerification,
                fakeServerName = matchedProject.fakeServerName,
                logicalHostSuffixes = normalizedSupportedHostSuffixes,
            )
        }
    }

    private fun findMatchingProject(payload: String): MatchedWattProject? {
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        val groups = root.optJSONArray(WATT_GROUPS_KEY) ?: return null
        return findMatchingProject(groups)
    }

    private fun findMatchingProject(groups: JSONArray): MatchedWattProject? {
        for (groupIndex in 0 until groups.length()) {
            val group = groups.optJSONObject(groupIndex) ?: continue
            val items = group.optJSONArray(WATT_ITEMS_KEY) ?: continue
            val matched = findMatchingProjectInItems(items)
            if (matched != null) {
                return matched
            }
        }
        return null
    }

    private fun findMatchingProjectInItems(items: JSONArray): MatchedWattProject? {
        for (itemIndex in 0 until items.length()) {
            val item = items.optJSONObject(itemIndex) ?: continue
            val configuredHosts = parseHosts(
                item.optString(WATT_MATCH_DOMAIN_NAMES_KEY),
                item.optString(WATT_LISTEN_DOMAIN_NAMES_KEY),
            )
            val logicalHosts = normalizedSupportedHosts.filterTo(LinkedHashSet()) { supportedHost ->
                configuredHosts.any { configuredHost ->
                    wattHostPatternMatches(configuredHost, supportedHost)
                }
            }
            if (
                logicalHosts.isNotEmpty() &&
                (routeProfile.allowUncheckedRoutes || item.optBoolean(WATT_CHECKED_KEY, true))
            ) {
                return MatchedWattProject(
                    logicalHosts = logicalHosts,
                    forwardTargets = parseForwardTargets(item.optString(WATT_FORWARD_DOMAIN_NAMES_KEY)),
                    proxyType = item.optInt(WATT_PROXY_TYPE_KEY, -1),
                    ignoreSslCertVerification = item.optBoolean(WATT_IGNORE_SSL_CERT_KEY),
                    fakeServerName = item.optString(WATT_FAKE_SERVER_NAME_KEY).trim(),
                )
            }
            val nestedItems = item.optJSONArray(WATT_ITEMS_KEY) ?: continue
            val nestedMatched = findMatchingProjectInItems(nestedItems)
            if (nestedMatched != null) {
                return nestedMatched
            }
        }
        return null
    }

    private data class MatchedWattProject(
        val logicalHosts: Set<String>,
        val forwardTargets: List<String>,
        val proxyType: Int,
        val ignoreSslCertVerification: Boolean,
        val fakeServerName: String,
    )

    private fun rankForwardTargets(targets: List<String>): List<String> {
        val distinctTargets = targets.distinct()
        if (distinctTargets.size < 2) {
            return distinctTargets
        }
        return distinctTargets
            .mapIndexed { index, target ->
                RankedWattForwardTarget(
                    target = target,
                    originalIndex = index,
                    probe = runCatching { forwardTargetProbe(target) }
                        .getOrDefault(WattToolkitForwardTargetProbe.failed()),
                )
            }
            .sortedWith(
                compareByDescending<RankedWattForwardTarget> { it.probe.successRate }
                    .thenBy { it.probe.latencyMs ?: Long.MAX_VALUE }
                    .thenBy { it.originalIndex },
            )
            .map(RankedWattForwardTarget::target)
    }

    private data class RankedWattForwardTarget(
        val target: String,
        val originalIndex: Int,
        val probe: WattToolkitForwardTargetProbe,
    )

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val ROUTE_CACHE_TTL_MS = 30L * 60L * 1_000L
        const val BACKGROUND_BEST_PATH_MIN_INTERVAL_MS = 5L * 60L * 1_000L
        const val FAILED_FORWARD_TARGET_TTL_MS = 30L * 60L * 1_000L
        const val SILENT_SWITCH_LATENCY_MARGIN_MS = 40L
        const val ROUTE_FETCH_ATTEMPTS = 5
        val ROUTE_FETCH_RETRY_DELAYS_MS = longArrayOf(250L, 500L, 1_000L, 1_500L)
        private val sharedBestPathBackgroundExecutor: Executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "watt-best-path-search").apply {
                    isDaemon = true
                }
            }
    }
}

internal data class WattToolkitGithubRoute(
    val logicalHosts: Set<String>,
    val forwardTargets: List<String>,
    val ignoreSslCertVerification: Boolean = false,
    val fakeServerName: String = "",
    /**
     * Subdomain suffixes covered by the same upstream rule as [logicalHosts].
     *
     * Without this a resolved route would reject sibling hosts (for example
     * avatars.githubusercontent.com) even though the rule that produced the
     * route already forwards the entire domain family.
     */
    val logicalHostSuffixes: Set<String> = emptySet(),
) {
    val forwardHosts: Set<String> = forwardTargets.mapNotNull(::extractForwardHost).toSet()
    val networkHosts: Set<String> = buildSet {
        addAll(forwardHosts)
        normalizedFakeServerName()?.let(::add)
        if (usesOriginFakeServerName()) {
            addAll(logicalHosts)
        }
    }

    fun buildForwardedUrl(originalUrl: HttpUrl): HttpUrl {
        val firstTarget = forwardTargets.firstOrNull()?.trim().orEmpty()
        if (firstTarget.isBlank()) {
            return originalUrl
        }
        return if (firstTarget.contains("://")) {
            val forwardedBase = firstTarget.toHttpUrlOrNull() ?: return originalUrl
            val networkHost = networkHostFor(originalUrl.host) ?: forwardedBase.host
            forwardedBase.newBuilder()
                .host(networkHost)
                .encodedPath(originalUrl.encodedPath)
                .encodedQuery(originalUrl.encodedQuery)
                .build()
        } else {
            originalUrl.newBuilder()
                .host(networkHostFor(originalUrl.host) ?: firstTarget)
                .build()
        }
    }

    fun normalizeLogicalUrl(
        url: HttpUrl,
        fallbackLogicalHost: String,
    ): HttpUrl {
        if (url.host.lowercase(Locale.ROOT) !in networkHosts) {
            return url
        }
        return url.newBuilder()
            .host(fallbackLogicalHost)
            .build()
    }

    fun matchesLogicalHost(host: String): Boolean {
        val normalizedHost = host.lowercase(Locale.ROOT)
        if (normalizedHost in logicalHosts) {
            return true
        }
        return logicalHostSuffixes.any { suffix -> normalizedHost.endsWith(suffix) }
    }

    fun shouldBypassHostnameVerification(host: String): Boolean =
        ignoreSslCertVerification && host.lowercase(Locale.ROOT) in networkHosts

    fun networkHostFor(logicalHost: String): String? {
        val fakeHost = normalizedFakeServerName()
        return when {
            fakeHost != null -> fakeHost
            usesOriginFakeServerName() -> logicalHost.lowercase(Locale.ROOT)
            else -> null
        }
    }

    fun usesOriginFakeServerName(): Boolean = fakeServerName.trim() in setOf("{origin}", "@domain")

    fun forwardTargetCandidates(): List<WattToolkitGithubRoute> {
        if (forwardTargets.size < 2) {
            return listOf(this)
        }
        return forwardTargets.indices.map { index ->
            copy(forwardTargets = forwardTargets.drop(index))
        }
    }

    private fun normalizedFakeServerName(): String? = fakeServerName
        .trim()
        .takeIf { it.isNotEmpty() && it != "{origin}" && it != "@domain" }
        ?.lowercase(Locale.ROOT)
}

internal class WattToolkitForwardDns(
    private val delegate: Dns = Dns.SYSTEM,
) : Dns {
    private val forwardHostsByNetworkHost = ConcurrentHashMap<String, String>()

    fun register(route: WattToolkitGithubRoute) {
        val targetHost = route.forwardHosts.firstOrNull() ?: return
        val fakeHost = route.fakeServerName
            .trim()
            .takeIf { it.isNotEmpty() && it != "{origin}" && it != "@domain" }
            ?.lowercase(Locale.ROOT)
        if (fakeHost != null) {
            forwardHostsByNetworkHost[fakeHost] = targetHost
        } else if (route.usesOriginFakeServerName()) {
            route.logicalHosts.forEach { logicalHost ->
                forwardHostsByNetworkHost[logicalHost.lowercase(Locale.ROOT)] = targetHost
            }
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val targetHost = forwardHostsByNetworkHost[hostname.lowercase(Locale.ROOT)] ?: hostname
        return delegate.lookup(targetHost)
    }
}

internal interface WattToolkitGithubRouteStore {
    fun load(): PersistedWattToolkitGithubRoute?

    fun save(route: PersistedWattToolkitGithubRoute)

    fun clear() = Unit
}

internal object NoOpWattToolkitGithubRouteStore : WattToolkitGithubRouteStore {
    override fun load(): PersistedWattToolkitGithubRoute? = null

    override fun save(route: PersistedWattToolkitGithubRoute) = Unit

    override fun clear() = Unit
}

internal class FileBackedWattToolkitGithubRouteStore(
    private val file: File,
    private val fallbackLogicalHosts: Set<String> = emptySet(),
    private val fallbackLogicalHostSuffixes: Set<String> = emptySet(),
) : WattToolkitGithubRouteStore {
    override fun load(): PersistedWattToolkitGithubRoute? {
        return runCatching {
            if (!file.isFile) {
                return null
            }
            val snapshot = JSONObject(file.readText())
            val logicalHosts = buildStringList(snapshot.optJSONArray("logicalHosts"))
                .ifEmpty { fallbackLogicalHosts.toList() }
                .map { it.lowercase(Locale.ROOT) }
                .toSet()
            val logicalHostSuffixes = buildStringList(snapshot.optJSONArray("logicalHostSuffixes"))
                .ifEmpty { fallbackLogicalHostSuffixes.toList() }
                .map { it.lowercase(Locale.ROOT) }
                .toSet()
            val forwardTargets = buildStringList(snapshot.optJSONArray("forwardTargets"))
            if (forwardTargets.isEmpty() || logicalHosts.isEmpty()) {
                return null
            }
            PersistedWattToolkitGithubRoute(
                route = WattToolkitGithubRoute(
                    logicalHosts = logicalHosts,
                    forwardTargets = forwardTargets,
                    ignoreSslCertVerification = snapshot.optBoolean("ignoreSslCertVerification"),
                    fakeServerName = snapshot.optString("fakeServerName").trim(),
                    logicalHostSuffixes = logicalHostSuffixes,
                ),
                cachedAtMs = snapshot.optLong("cachedAtMs"),
            )
        }.getOrNull()
    }

    override fun save(route: PersistedWattToolkitGithubRoute) {
        runCatching {
            file.parentFile?.mkdirs()
            val snapshot = JSONObject().apply {
                put("cachedAtMs", route.cachedAtMs)
                put("logicalHosts", JSONArray(route.route.logicalHosts.sorted()))
                put("forwardTargets", JSONArray(route.route.forwardTargets))
                put("ignoreSslCertVerification", route.route.ignoreSslCertVerification)
                put("fakeServerName", route.route.fakeServerName)
                put("logicalHostSuffixes", JSONArray(route.route.logicalHostSuffixes.sorted()))
            }
            val parentDir = file.parentFile ?: file.absoluteFile.parentFile
            val tempFile = File.createTempFile(file.name, ".tmp", parentDir)
            tempFile.writeText(snapshot.toString())
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
        }
    }

    override fun clear() {
        runCatching { file.delete() }
    }
}

internal data class PersistedWattToolkitGithubRoute(
    val route: WattToolkitGithubRoute,
    val cachedAtMs: Long,
)

internal fun defaultWattToolkitRouteClient(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(DEFAULT_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(DEFAULT_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(DEFAULT_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .proxy(Proxy.NO_PROXY)
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
        .build()

private fun defaultBootstrapRouteForProfile(routeProfile: WattToolkitRouteProfile): WattToolkitGithubRoute? =
    routeProfile.bootstrapForwardTargets
        .takeIf(List<String>::isNotEmpty)
        ?.let { forwardTargets ->
            WattToolkitGithubRoute(
                logicalHosts = routeProfile.supportedHosts.map { it.lowercase(Locale.ROOT) }.toSet(),
                forwardTargets = forwardTargets,
                ignoreSslCertVerification = true,
                fakeServerName = "",
            )
        }

private fun extractForwardHost(target: String): String? {
    val normalized = target.trim()
    if (normalized.isEmpty()) {
        return null
    }
    return if (normalized.contains("://")) {
        normalized.toHttpUrlOrNull()?.host?.lowercase(Locale.ROOT)
    } else {
        normalized.lowercase(Locale.ROOT)
    }
}

private fun parseForwardTargets(raw: String): List<String> =
    raw.split(';')
        .map(String::trim)
        .filter(String::isNotEmpty)

internal data class WattToolkitForwardTargetProbe(
    val successes: Int,
    val attempts: Int,
    val latencyMs: Long?,
) {
    val successRate: Double
        get() = if (attempts <= 0) 0.0 else successes.toDouble() / attempts.toDouble()

    companion object {
        fun failed(attempts: Int = FORWARD_TARGET_PROBE_ATTEMPTS): WattToolkitForwardTargetProbe =
            WattToolkitForwardTargetProbe(successes = 0, attempts = attempts, latencyMs = null)
    }
}

private fun probeWattToolkitForwardTarget(target: String): WattToolkitForwardTargetProbe {
    val url = target.toHttpUrlOrNull()
        ?: "https://$target".toHttpUrlOrNull()
        ?: return WattToolkitForwardTargetProbe.failed()
    var successes = 0
    val latencies = ArrayList<Long>(FORWARD_TARGET_PROBE_ATTEMPTS)
    repeat(FORWARD_TARGET_PROBE_ATTEMPTS) {
        val startedAt = System.nanoTime()
        try {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(url.host, url.port),
                    FORWARD_TARGET_PROBE_TIMEOUT_MS.toInt(),
                )
            }
            successes++
            latencies += ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L)
        } catch (_: IOException) {
            // Keep probing remaining samples so transient loss affects success rate.
        }
    }
    return WattToolkitForwardTargetProbe(
        successes = successes,
        attempts = FORWARD_TARGET_PROBE_ATTEMPTS,
        latencyMs = latencies.takeIf(List<Long>::isNotEmpty)?.average()?.toLong(),
    )
}

private fun parseHosts(vararg hostGroups: String): Set<String> {
    val hosts = LinkedHashSet<String>()
    hostGroups.forEach { group ->
        group.split(';').forEach { rawHost ->
            val normalized = rawHost.trim()
            if (normalized.isEmpty()) {
                return@forEach
            }
            val parsedHost = if ("://" in normalized) {
                normalized.toHttpUrlOrNull()?.host
            } else {
                normalized
            } ?: return@forEach
            val lowerHost = parsedHost.lowercase(Locale.ROOT)
            if (lowerHost == "*" || lowerHost.count { it == '*' } > 1 ||
                ("*" in lowerHost && !lowerHost.startsWith("*."))
            ) {
                return@forEach
            }
            hosts += lowerHost
        }
    }
    return hosts
}

private fun wattHostPatternMatches(pattern: String, host: String): Boolean {
    val normalizedPattern = pattern.lowercase(Locale.ROOT)
    val normalizedHost = host.lowercase(Locale.ROOT)
    if (normalizedPattern == normalizedHost) {
        return true
    }
    val wildcardSuffix = normalizedPattern.removePrefix("*.")
    return normalizedPattern.startsWith("*.") &&
        (normalizedHost == wildcardSuffix || normalizedHost.endsWith(".$wildcardSuffix"))
}

private fun buildStringList(array: JSONArray?): List<String> {
    if (array == null) {
        return emptyList()
    }
    val values = ArrayList<String>(array.length())
    for (index in 0 until array.length()) {
        val value = array.optString(index).trim()
        if (value.isNotEmpty()) {
            values += value
        }
    }
    return values
}

private fun Throwable.isRetryableWattRouteFetchFailure(): Boolean =
    this is IOException || cause?.isRetryableWattRouteFetchFailure() == true

private fun Response.redirectTarget(
    logicalUrl: HttpUrl,
    route: WattToolkitGithubRoute?,
): HttpUrl? {
    if (code !in REDIRECT_RESPONSE_CODES) {
        return null
    }
    val location = header("Location")?.trim().orEmpty()
    if (location.isBlank()) {
        return null
    }
    return logicalUrl.resolve(location)?.let { resolvedUrl ->
        route?.normalizeLogicalUrl(
            url = resolvedUrl,
            fallbackLogicalHost = logicalUrl.host,
        ) ?: resolvedUrl
    }
}

private const val WATT_ACCELERATOR_PROJECTGROUPS_URL = "https://api.steampp.net/accelerator/projectgroups"
private const val WATT_GROUPS_KEY = "\uD83E\uDD93"
private const val WATT_ITEMS_KEY = "Items"
private const val WATT_MATCH_DOMAIN_NAMES_KEY = "MatchDomainNames"
private const val WATT_LISTEN_DOMAIN_NAMES_KEY = "ListenDomainNames"
private const val WATT_FORWARD_DOMAIN_NAMES_KEY = "ForwardDomainNames"
private const val WATT_PROXY_TYPE_KEY = "ProxyType"
private const val WATT_IGNORE_SSL_CERT_KEY = "IgnoreSSLCertVerification"
private const val WATT_FAKE_SERVER_NAME_KEY = "FakeServerName"
private const val WATT_CHECKED_KEY = "Checked"
internal const val WATT_PROXY_TYPE_DIRECT = 0
internal const val WATT_PROXY_TYPE_REVERSE_PROXY = 1
private const val DEFAULT_CONNECT_TIMEOUT_MS = 8_000L
private const val DEFAULT_READ_TIMEOUT_MS = 18_000L
private const val FORWARD_TARGET_PROBE_ATTEMPTS = 3
private const val FORWARD_TARGET_PROBE_TIMEOUT_MS = 1_200L
private const val MAX_FOLLOW_UPS = 10
private const val HTTP_METHOD_GET = "GET"
private const val HTTP_METHOD_HEAD = "HEAD"
private const val HTTP_TEMP_REDIRECT = 307
private const val HTTP_PERM_REDIRECT = 308
private val REDIRECT_RESPONSE_CODES = setOf(300, 301, 302, 303, HTTP_TEMP_REDIRECT, HTTP_PERM_REDIRECT)
private val STALE_ROUTE_RESPONSE_CODES = setOf(400, 404)

/**
 * Validates certificates through the platform trust manager, relaxing the check
 * only for forward hosts whose upstream rule opted out of chain verification.
 *
 * Extends [X509ExtendedTrustManager] because the peer host is required to make
 * that decision, and the two-argument [X509TrustManager] callbacks do not carry
 * it. The two-argument overloads therefore always validate strictly.
 */
private class WattToolkitForwardTrustManager(
    private val delegate: X509TrustManager,
    private val unsafeHostProvider: (String) -> Boolean,
) : X509ExtendedTrustManager() {
    private val extendedDelegate = delegate as? X509ExtendedTrustManager

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        delegate.checkServerTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
        val peerHost = (socket as? SSLSocket)?.let { sslSocket ->
            runCatching { sslSocket.handshakeSession?.peerHost }.getOrNull()
        } ?: socket?.inetAddress?.hostName
        if (isUnsafeAllowed(peerHost)) {
            return
        }
        extendedDelegate?.checkServerTrusted(chain, authType, socket)
            ?: delegate.checkServerTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
        if (isUnsafeAllowed(engine?.peerHost)) {
            return
        }
        extendedDelegate?.checkServerTrusted(chain, authType, engine)
            ?: delegate.checkServerTrusted(chain, authType)
    }

    private fun isUnsafeAllowed(peerHost: String?): Boolean {
        val host = peerHost?.trim()?.takeIf(String::isNotEmpty) ?: return false
        return unsafeHostProvider(host)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
}
