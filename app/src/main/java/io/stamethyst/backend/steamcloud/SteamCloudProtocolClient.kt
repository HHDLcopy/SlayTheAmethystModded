package io.stamethyst.backend.steamcloud

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import top.apricityx.workshop.steam.protocol.OkHttpSteamCmSession
import top.apricityx.workshop.steam.protocol.SteamAccountSession
import top.apricityx.workshop.steam.protocol.SteamDirectoryClient
import top.apricityx.workshop.steam.protocol.SteamCmSession
import top.apricityx.workshop.steam.protocol.SteamPacketCodec
import top.apricityx.workshop.steam.proto.CPlayer_GetUserStats_Request
import top.apricityx.workshop.steam.proto.CPlayer_GetUserStats_Response
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesCloudSteamclient
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserver
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverUserstats
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverFriends

/** Synchronous app facade over the proxy-capable protocol session. */
internal class SteamCloudProtocolClient(
    private val httpClient: OkHttpClient,
) : AutoCloseable {
    private val session: SteamCmSession = OkHttpSteamCmSession(httpClient)
    private val directory = SteamDirectoryClient(httpClient)

    fun logOn(accountName: String, refreshToken: String, steamId64: String): Long = runBlocking {
        val servers = directory.loadServers()
        session.connectWithRefreshToken(
            servers,
            SteamAccountSession(
                accountName = accountName,
                steamId = steamId64.toLongOrNull() ?: 0L,
                refreshToken = refreshToken,
            ),
        ).steamId
    }

    fun getUserStats(appId: Long, steamId64: Long, timeoutMs: Long): SteammessagesClientserverUserstats.CMsgClientGetUserStatsResponse =
        runBlocking {
            val request = SteammessagesClientserverUserstats.CMsgClientGetUserStats.newBuilder()
                .setGameId(appId)
                .setSteamIdForUser(steamId64)
                .setSchemaLocalVersion(0)
                .setCrcStats(0)
                .build()
            withTimeout(timeoutMs) {
                session.sendClientMessage(
                    SteamPacketCodec.emsgClientGetUserStats,
                    request,
                    SteamPacketCodec.emsgClientGetUserStatsResponse,
                    SteammessagesClientserverUserstats.CMsgClientGetUserStatsResponse.parser(),
                    appId.toUInt(),
                )
            }
        }

    fun getPlayerUserStats(
        appId: Long,
        steamId64: Long,
        crcStats: Int,
        timeoutMs: Long,
    ): CPlayer_GetUserStats_Response = runBlocking {
        withTimeout(timeoutMs) {
            session.callServiceMethod(
                "Player.GetUserStats#1",
                CPlayer_GetUserStats_Request.newBuilder()
                    .setSteamid(steamId64)
                    .setAppid(appId.toInt())
                    .setCrcStats(crcStats)
                    .build(),
                CPlayer_GetUserStats_Response.parser(),
            )
        }
    }

    fun storeUserStat(
        appId: Long,
        steamId64: Long,
        crcStats: Int,
        statId: Int,
        statValue: Int,
        timeoutMs: Long,
    ): SteammessagesClientserverUserstats.CMsgClientStoreUserStatsResponse = runBlocking {
        val stat = SteammessagesClientserverUserstats.CMsgClientStoreUserStats2.Stats
            .newBuilder()
            .setStatId(statId)
            .setStatValue(statValue)
            .build()
        val request = SteammessagesClientserverUserstats.CMsgClientStoreUserStats2.newBuilder()
            .setGameId(appId)
            .setSettorSteamId(steamId64)
            .setSetteeSteamId(steamId64)
            .setCrcStats(crcStats)
            .setExplicitReset(false)
            .addStats(stat)
            .build()
        withTimeout(timeoutMs) {
            session.sendClientMessage(
                SteamPacketCodec.emsgClientStoreUserStats2,
                request,
                SteamPacketCodec.emsgClientStoreUserStatsResponse,
                SteammessagesClientserverUserstats.CMsgClientStoreUserStatsResponse.parser(),
                appId.toUInt(),
            )
        }
    }

    fun sendGamesPlayed(appId: Long) = runBlocking {
        val builder = SteammessagesClientserver.CMsgClientGamesPlayed.newBuilder()
        if (appId > 0) {
            builder.addGamesPlayed(
                SteammessagesClientserver.CMsgClientGamesPlayed.GamePlayed
                    .newBuilder().setGameId(appId).build(),
            )
        }
        session.sendClientMessage(SteamPacketCodec.emsgClientGamesPlayedWithDataBlob, builder.build())
    }

    fun sendPersonaOnline() = runBlocking {
        val request = SteammessagesClientserverFriends.CMsgClientChangeStatus.newBuilder()
            .setPersonaState(1)
            .setPersonaSetByUser(true)
            .build()
        session.sendClientMessage(SteamPacketCodec.emsgClientChangeStatus, request)
    }

    fun getAppFileChangelist(appId: Int): SteammessagesCloudSteamclient.CCloud_GetAppFileChangelist_Response = service(
        "Cloud.GetAppFileChangelist#1",
        SteammessagesCloudSteamclient.CCloud_GetAppFileChangelist_Request.newBuilder().setAppid(appId).build(),
        SteammessagesCloudSteamclient.CCloud_GetAppFileChangelist_Response.parser(),
    )

    fun clientFileDownload(appId: Int, filename: String): SteammessagesCloudSteamclient.CCloud_ClientFileDownload_Response = service(
        "Cloud.ClientFileDownload#1",
        SteammessagesCloudSteamclient.CCloud_ClientFileDownload_Request.newBuilder()
            .setAppid(appId).setFilename(filename).setRealm(1).setForceProxy(false).build(),
        SteammessagesCloudSteamclient.CCloud_ClientFileDownload_Response.parser(),
    )

    fun beginAppUploadBatch(request: SteammessagesCloudSteamclient.CCloud_BeginAppUploadBatch_Request) = service(
        "Cloud.BeginAppUploadBatch#1", request,
        SteammessagesCloudSteamclient.CCloud_BeginAppUploadBatch_Response.parser(),
    )

    fun beginHttpUpload(request: SteammessagesCloudSteamclient.CCloud_BeginHTTPUpload_Request) = service(
        "Cloud.BeginHTTPUpload#1", request,
        SteammessagesCloudSteamclient.CCloud_BeginHTTPUpload_Response.parser(),
    )

    fun commitHttpUpload(request: SteammessagesCloudSteamclient.CCloud_CommitHTTPUpload_Request) = service(
        "Cloud.CommitHTTPUpload#1", request,
        SteammessagesCloudSteamclient.CCloud_CommitHTTPUpload_Response.parser(),
    )

    fun completeAppUploadBatch(request: SteammessagesCloudSteamclient.CCloud_CompleteAppUploadBatch_Request) = service(
        "Cloud.CompleteAppUploadBatch#1", request,
        SteammessagesCloudSteamclient.CCloud_CompleteAppUploadBatch_Response.parser(),
    )

    private fun <T : com.google.protobuf.MessageLite> service(
        method: String,
        request: com.google.protobuf.MessageLite,
        parser: com.google.protobuf.Parser<T>,
    ): T = runBlocking { session.callServiceMethod(method, request, parser) }

    override fun close() {
        session.close()
    }
}
