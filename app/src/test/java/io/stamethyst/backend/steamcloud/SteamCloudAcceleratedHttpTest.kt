package io.stamethyst.backend.steamcloud

import io.stamethyst.backend.github.ExperimentalGithubDirectAccessRuntime
import io.stamethyst.backend.github.ExperimentalGithubDirectAccessInterceptor
import io.stamethyst.backend.github.GithubDirectHostnameVerifier
import io.stamethyst.backend.github.WattToolkitGithubRouteResolver
import java.net.InetAddress
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SteamCloudAcceleratedHttpTest {
    private lateinit var apiServer: MockWebServer
    private lateinit var steamStoreForwardServer: MockWebServer
    private lateinit var steamContentForwardServer: MockWebServer

    @Before
    fun setUp() {
        apiServer = MockWebServer()
        steamStoreForwardServer = MockWebServer()
        steamContentForwardServer = MockWebServer()
        apiServer.start()
        steamStoreForwardServer.start()
        steamContentForwardServer.start()
    }

    @After
    fun tearDown() {
        apiServer.close()
        steamStoreForwardServer.close()
        steamContentForwardServer.close()
        SteamCloudAcceleratedHttp.clearRuntimeCacheForTests()
    }

    @Test
    fun routeResolver_matchesSteamStoreHostsFromWattRules() {
        apiServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "🦓": [
                        {
                          "Items": [
                            {
                              "MatchDomainNames": "store.steampowered.com;api.steampowered.com;login.steampowered.com",
                              "ListenDomainNames": "store.steampowered.com;api.steampowered.com;login.steampowered.com",
                              "ForwardDomainNames": "http://steamstore.rmbgame.net:${steamStoreForwardServer.port}",
                              "ProxyType": 0,
                              "IgnoreSSLCertVerification": true
                            }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = SteamStoreWattToolkitRouteProfile,
            client = OkHttpClient.Builder().dns(dns).build(),
            projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
        )

        val route = resolver.resolveRouteForHost("login.steampowered.com")

        assertNotNull(route)
        assertTrue(route!!.logicalHosts.contains("api.steampowered.com"))
        assertTrue(route.logicalHosts.contains("login.steampowered.com"))
        assertEquals(
            "steamstore.rmbgame.net",
            route.buildForwardedUrl(
                "https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/"
                    .toHttpUrl(),
            ).host,
        )
    }

    @Test
    fun steamImageRouteProfile_supportsFastlyAvatarHost() {
        assertTrue(
            SteamImageCdnWattToolkitRouteProfile.supportedHosts.contains(
                "avatars.fastly.steamstatic.com",
            ),
        )
    }

    @Test
    fun steamContentRouteProfile_supportsSteamPipeCdnHostsSeenOnDevice() {
        val expectedHosts = setOf(
            "st.dl.eccdnx.com",
            "xz.pphimalayanrt.com",
            "dl.steam.clngaa.com",
            "files.steam.nsclouds.cn",
        )

        assertTrue(
            SteamContentCdnWattToolkitRouteProfile.supportedHosts.containsAll(expectedHosts),
        )
    }

    @Test
    fun routeResolver_matchesSteamContentCdnHostsFromWattProxyRule() {
        apiServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "🦓": [
                        {
                          "Items": [
                            {
                              "MatchDomainNames": "st.dl.eccdnx.com;xz.pphimalayanrt.com;dl.steam.clngaa.com;files.steam.nsclouds.cn",
                              "ListenDomainNames": "st.dl.eccdnx.com;xz.pphimalayanrt.com;dl.steam.clngaa.com;files.steam.nsclouds.cn",
                              "ForwardDomainNames": "http://cdn.queniuqe.com:${steamContentForwardServer.port}",
                              "ProxyType": 1,
                              "IgnoreSSLCertVerification": true
                            }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = SteamContentCdnWattToolkitRouteProfile,
            client = OkHttpClient.Builder().dns(dns).build(),
            projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
        )

        val route = resolver.resolveRouteForHost("xz.pphimalayanrt.com")

        assertNotNull(route)
        assertTrue(route!!.logicalHosts.contains("xz.pphimalayanrt.com"))
        assertTrue(route.logicalHosts.contains("st.dl.eccdnx.com"))
        assertTrue(route.logicalHosts.contains("dl.steam.clngaa.com"))
        assertTrue(route.logicalHosts.contains("files.steam.nsclouds.cn"))
        assertEquals(
            "cdn.queniuqe.com",
            route.buildForwardedUrl(
                "https://xz.pphimalayanrt.com/depot/646571/manifest/1616206291221819177/5".toHttpUrl(),
            ).host,
        )
    }

    @Test
    fun interceptor_routesSteamApiRequestThroughWattForwardTarget() {
        apiServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "🦓": [
                        {
                          "Items": [
                            {
                              "MatchDomainNames": "store.steampowered.com;api.steampowered.com;login.steampowered.com",
                              "ListenDomainNames": "store.steampowered.com;api.steampowered.com;login.steampowered.com",
                              "ForwardDomainNames": "http://steamstore.rmbgame.net:${steamStoreForwardServer.port}",
                              "ProxyType": 0,
                              "IgnoreSSLCertVerification": true
                            }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        steamStoreForwardServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"response":{"result":1}}""")
                .build(),
        )

        val dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = SteamStoreWattToolkitRouteProfile,
            client = OkHttpClient.Builder().dns(dns).build(),
            projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
        )
        val runtime = ExperimentalGithubDirectAccessRuntime(
            resolvers = listOf(resolver),
            hostnameVerifier = GithubDirectHostnameVerifier { host ->
                resolver.allowsUnsafeHostnameBypass(host)
            },
            directHttpClient = OkHttpClient.Builder()
                .dns(dns)
                .followRedirects(false)
                .followSslRedirects(false)
                .build(),
        )
        val client = OkHttpClient.Builder()
            .dns(dns)
            .addInterceptor(
                ExperimentalGithubDirectAccessInterceptor(
                    routeResolvers = runtime.resolvers,
                    directCallFactory = runtime.directHttpClient,
                ),
            )
            .build()

        client.newCall(
            Request.Builder()
                .url("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/")
                .post("itemcount=0".toRequestBody())
                .build(),
        ).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("api.steampowered.com", response.request.url.host)
        }

        val routeRequest = apiServer.takeRequest()
        assertEquals("/accelerator/projectgroups", routeRequest.url.encodedPath)

        val forwardedRequest = steamStoreForwardServer.takeRequest()
        assertEquals(
            "/ISteamRemoteStorage/GetPublishedFileDetails/v1/",
            forwardedRequest.url.encodedPath,
        )
        assertEquals("api.steampowered.com", forwardedRequest.headers["Host"])
    }

    @Test
    fun interceptor_routesSteamContentCdnRequestThroughWattForwardTarget() {
        apiServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "🦓": [
                        {
                          "Items": [
                            {
                              "MatchDomainNames": "st.dl.eccdnx.com;xz.pphimalayanrt.com;dl.steam.clngaa.com;files.steam.nsclouds.cn",
                              "ListenDomainNames": "st.dl.eccdnx.com;xz.pphimalayanrt.com;dl.steam.clngaa.com;files.steam.nsclouds.cn",
                              "ForwardDomainNames": "http://cdn.queniuqe.com:${steamContentForwardServer.port}",
                              "ProxyType": 1,
                              "IgnoreSSLCertVerification": true
                            }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        steamContentForwardServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("manifest-bytes")
                .build(),
        )

        val dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = SteamContentCdnWattToolkitRouteProfile,
            client = OkHttpClient.Builder().dns(dns).build(),
            projectGroupsUrl = apiServer.url("/accelerator/projectgroups"),
        )
        val runtime = ExperimentalGithubDirectAccessRuntime(
            resolvers = listOf(resolver),
            hostnameVerifier = GithubDirectHostnameVerifier { host ->
                resolver.allowsUnsafeHostnameBypass(host)
            },
            directHttpClient = OkHttpClient.Builder()
                .dns(dns)
                .followRedirects(false)
                .followSslRedirects(false)
                .build(),
        )
        val client = OkHttpClient.Builder()
            .dns(dns)
            .addInterceptor(
                ExperimentalGithubDirectAccessInterceptor(
                    routeResolvers = runtime.resolvers,
                    directCallFactory = runtime.directHttpClient,
                ),
            )
            .build()

        client.newCall(
            Request.Builder()
                .url("https://st.dl.eccdnx.com/depot/646571/manifest/1616206291221819177/5")
                .build(),
        ).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("st.dl.eccdnx.com", response.request.url.host)
        }

        val routeRequest = apiServer.takeRequest()
        assertEquals("/accelerator/projectgroups", routeRequest.url.encodedPath)

        val forwardedRequest = steamContentForwardServer.takeRequest()
        assertEquals(
            "/depot/646571/manifest/1616206291221819177/5",
            forwardedRequest.url.encodedPath,
        )
        assertEquals("st.dl.eccdnx.com", forwardedRequest.headers["Host"])
    }
}
