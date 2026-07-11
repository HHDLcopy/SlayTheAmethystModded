package io.stamethyst.backend.easytier

import android.content.Context
import android.os.Build
import io.stamethyst.BuildConfig
import java.io.IOException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal class EasyTierRoomApiHttpException(
    val statusCode: Int,
    message: String,
) : IOException(message)

internal class EasyTierRoomApiClient(
    private val context: Context,
    private val client: OkHttpClient = defaultHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun startSession(
        roomId: String,
        playerId: String,
        displayName: String,
        allowNewJoinsWhenCreating: Boolean? = null,
        sessionToken: String = "",
        ownerToken: String = "",
    ): EasyTierRoomSessionConfig {
        val config = EasyTierConfigRepository.current()
        val baseUrl = config.roomApiBaseUrl.trim()
        require(baseUrl.isNotEmpty()) { "EasyTier room API base URL is unavailable." }
        require(roomId.isNotBlank()) { "Room ID is required." }

        val requestBody = json.encodeToString(
            StartSessionRequest.serializer(),
            StartSessionRequest(
                roomId = roomId.trim(),
                playerId = playerId.trim(),
                displayName = displayName.trim(),
                clientVersion = BuildConfig.VERSION_NAME,
                deviceSummary = buildDeviceSummary(),
                allowNewJoins = allowNewJoinsWhenCreating,
            )
        )
        val request = Request.Builder()
            .url(apiUrl(baseUrl, "api", "lan", "session", "start"))
            .header("User-Agent", "SlayTheAmethyst/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/json")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .applyLanCredentials(sessionToken, ownerToken)
            .build()

        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw EasyTierRoomApiHttpException(
                    statusCode = response.code,
                    message = buildString {
                        append("EasyTier Room API start session failed: HTTP ")
                        append(response.code)
                        response.message.takeIf { it.isNotBlank() }?.let {
                            append(' ').append(it)
                        }
                        responseText.trim().takeIf { it.isNotEmpty() }?.let {
                            append(" - ").append(it)
                        }
                    }
                )
            }
            return parseStartSessionResponse(responseText)
        }
    }

    fun stopSession(sessionId: String, sessionToken: String) {
        val config = EasyTierConfigRepository.current()
        val baseUrl = config.roomApiBaseUrl.trim()
        require(baseUrl.isNotEmpty()) { "EasyTier room API base URL is unavailable." }
        require(sessionId.isNotBlank()) { "Session ID is required." }

        val requestBody = json.encodeToString(
            StopSessionRequest.serializer(),
            StopSessionRequest(sessionId = sessionId.trim())
        )
        val request = Request.Builder()
            .url(apiUrl(baseUrl, "api", "lan", "session", "stop"))
            .header("User-Agent", "SlayTheAmethyst/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/json")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .applyLanSessionToken(sessionToken)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val responseText = response.body?.string().orEmpty()
                throw EasyTierRoomApiHttpException(
                    statusCode = response.code,
                    message = "EasyTier Room API stop session failed: HTTP ${response.code}" +
                        response.message.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty() +
                        responseText.trim().takeIf { it.isNotEmpty() }?.let { " - $it" }.orEmpty()
                )
            }
        }
    }

    fun fetchSessionStatus(sessionId: String, sessionToken: String): EasyTierSessionStatusSnapshot {
        val config = EasyTierConfigRepository.current()
        val baseUrl = config.roomApiBaseUrl.trim()
        require(baseUrl.isNotEmpty()) { "EasyTier room API base URL is unavailable." }
        require(sessionId.isNotBlank()) { "Session ID is required." }

        val request = Request.Builder()
            .url(
                apiUrl(
                    baseUrl,
                    "api",
                    "lan",
                    "session",
                    "status",
                    queryParameters = mapOf(
                        "sessionId" to sessionId.trim(),
                    ),
                )
            )
            .header("User-Agent", "SlayTheAmethyst/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/json")
            .applyLanSessionToken(sessionToken)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw EasyTierRoomApiHttpException(
                    statusCode = response.code,
                    message = "EasyTier Room API session status failed: HTTP ${response.code}",
                )
            }
            return parseSessionStatusResponse(responseText)
        }
    }

    fun reportSessionRuntime(
        sessionId: String,
        sessionToken: String,
        assignedIpv4Cidr: String,
        relayServerDescription: String = "",
    ): EasyTierSessionStatusSnapshot {
        val config = EasyTierConfigRepository.current()
        val baseUrl = config.roomApiBaseUrl.trim()
        require(baseUrl.isNotEmpty()) { "EasyTier room API base URL is unavailable." }
        require(sessionId.isNotBlank()) { "Session ID is required." }
        require(assignedIpv4Cidr.isNotBlank()) { "Assigned IPv4 CIDR is required." }

        val requestBody = json.encodeToString(
            ReportSessionRuntimeRequest.serializer(),
            ReportSessionRuntimeRequest(
                sessionId = sessionId.trim(),
                assignedIpv4Cidr = assignedIpv4Cidr.trim(),
                relayServerDescription = relayServerDescription.trim(),
            )
        )
        val request = Request.Builder()
            .url(apiUrl(baseUrl, "api", "lan", "session", "runtime"))
            .header("User-Agent", "SlayTheAmethyst/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/json")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .applyLanSessionToken(sessionToken)
            .build()
        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw EasyTierRoomApiHttpException(
                    statusCode = response.code,
                    message = "EasyTier Room API session runtime failed: HTTP ${response.code}" +
                        response.message.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty() +
                        responseText.trim().takeIf { it.isNotEmpty() }?.let { " - $it" }.orEmpty(),
                )
            }
            return parseSessionStatusResponse(responseText)
        }
    }

    fun fetchRoomInfo(roomId: String): EasyTierRoomInfo {
        val config = EasyTierConfigRepository.current()
        val baseUrl = config.roomApiBaseUrl.trim()
        require(baseUrl.isNotEmpty()) { "EasyTier room API base URL is unavailable." }
        require(roomId.isNotBlank()) { "Room ID is required." }

        val request = Request.Builder()
            .url(apiUrl(baseUrl, "api", "lan", "rooms", roomId.trim()))
            .header("User-Agent", "SlayTheAmethyst/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw EasyTierRoomApiHttpException(
                    statusCode = response.code,
                    message = "EasyTier Room API room info failed: HTTP ${response.code}",
                )
            }
            return parseRoomInfoResponse(responseText)
        }
    }

    fun listRooms(limit: Int = 50): List<EasyTierRoomListItem> {
        val config = EasyTierConfigRepository.current()
        val baseUrl = config.roomApiBaseUrl.trim()
        require(baseUrl.isNotEmpty()) { "EasyTier room API base URL is unavailable." }

        val resolvedLimit = limit.coerceIn(1, 50)
        val rooms = mutableListOf<EasyTierRoomListItem>()
        var offset = 0
        var pageCount = 0
        while (pageCount < 200) {
            val page = fetchRoomListPage(baseUrl, resolvedLimit, offset)
            rooms += page.rooms
            val nextOffset = page.nextOffset ?: break
            if (nextOffset <= offset) {
                break
            }
            offset = nextOffset
            pageCount += 1
        }
        return rooms.distinctBy { room -> room.roomId }
    }

    private fun fetchRoomListPage(
        baseUrl: String,
        limit: Int,
        offset: Int,
    ): EasyTierRoomListPage {
        val request = Request.Builder()
            .url(
                apiUrl(
                    baseUrl,
                    "api",
                    "lan",
                    "rooms",
                    queryParameters = mapOf(
                        "limit" to limit.toString(),
                        "offset" to offset.toString(),
                    ),
                )
            )
            .header("User-Agent", "SlayTheAmethyst/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw EasyTierRoomApiHttpException(
                    statusCode = response.code,
                    message = "EasyTier Room API room list failed: HTTP ${response.code}",
                )
            }
            return parseRoomListPage(responseText)
        }
    }

    fun lockRoom(roomId: String, ownerToken: String): EasyTierRoomInfo =
        mutateRoom(roomId, ownerToken, "lock")

    fun unlockRoom(roomId: String, ownerToken: String): EasyTierRoomInfo =
        mutateRoom(roomId, ownerToken, "unlock")

    fun closeRoom(roomId: String, ownerToken: String): EasyTierRoomInfo =
        mutateRoom(roomId, ownerToken, "close")

    private fun mutateRoom(
        roomId: String,
        ownerToken: String,
        action: String,
    ): EasyTierRoomInfo {
        val config = EasyTierConfigRepository.current()
        val baseUrl = config.roomApiBaseUrl.trim()
        require(baseUrl.isNotEmpty()) { "EasyTier room API base URL is unavailable." }
        require(roomId.isNotBlank()) { "Room ID is required." }
        require(ownerToken.isNotBlank()) { "Room owner credential is required." }

        val requestBody = json.encodeToString(
            UpdateRoomRequest.serializer(),
            UpdateRoomRequest(
                action = action,
            )
        )
        val request = Request.Builder()
            .url(apiUrl(baseUrl, "api", "lan", "rooms", roomId.trim(), "action"))
            .header("User-Agent", "SlayTheAmethyst/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/json")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .applyLanOwnerToken(ownerToken)
            .build()

        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw EasyTierRoomApiHttpException(
                    statusCode = response.code,
                    message = "EasyTier Room API room action failed: HTTP ${response.code}" +
                        response.message.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty() +
                        responseText.trim().takeIf { it.isNotEmpty() }?.let { " - $it" }.orEmpty()
                )
            }
            return parseRoomInfoResponse(responseText)
        }
    }

    internal fun parseStartSessionResponse(responseText: String): EasyTierRoomSessionConfig {
        val payload = json.decodeFromString(StartSessionResponse.serializer(), responseText)
        return EasyTierRoomSessionConfig(
            sessionId = payload.sessionId.trim(),
            roomId = payload.roomId.trim(),
            mode = EasyTierNetworkMode.fromCloudControl(payload.mode),
            entryNodeUrl = payload.entryNodeUrl.trim(),
            configServerUrl = payload.configServerUrl.trim(),
            aclGroup = payload.aclGroup.trim(),
            networkSecret = payload.networkSecret.trim(),
            sessionToken = payload.sessionToken.trim(),
            ownerToken = payload.ownerToken.trim(),
            expiresAtEpochSeconds = payload.expiresAtEpochSeconds,
        )
    }

    internal fun parseSessionStatusResponse(responseText: String): EasyTierSessionStatusSnapshot {
        val payload = json.decodeFromString(SessionStatusResponse.serializer(), responseText)
        return EasyTierSessionStatusSnapshot(
            sessionId = payload.sessionId.trim(),
            roomId = payload.roomId.trim(),
            sessionState = payload.sessionState.trim(),
            roomState = payload.roomState.trim(),
            peerCount = payload.peerCount,
            assignedIpv4Cidr = payload.assignedIpv4Cidr.trim(),
            relayServerDescription = payload.relayServerDescription.trim(),
        )
    }

    internal fun parseRoomInfoResponse(responseText: String): EasyTierRoomInfo {
        val payload = json.decodeFromString(RoomInfoResponse.serializer(), responseText)
        return EasyTierRoomInfo(
            roomId = payload.roomId.trim(),
            ownerPlayerId = payload.ownerPlayerId.trim(),
            ownerDisplayName = payload.ownerDisplayName.trim(),
            mode = EasyTierNetworkMode.fromCloudControl(payload.mode),
            allowNewJoins = payload.allowNewJoins,
            closedAtMs = payload.closedAtMs,
            memberCount = payload.memberCount,
            members = payload.members.map { member ->
                EasyTierRoomMember(
                    playerId = member.playerId.trim(),
                    displayName = member.displayName.trim(),
                    role = member.role.trim(),
                    online = member.online,
                    assignedIpv4Cidr = member.assignedIpv4Cidr.trim(),
                )
            },
        )
    }

    internal fun parseRoomListResponse(responseText: String): List<EasyTierRoomListItem> {
        return parseRoomListPage(responseText).rooms
    }

    internal fun parseRoomListPage(responseText: String): EasyTierRoomListPage {
        val payload = json.decodeFromString(RoomListResponse.serializer(), responseText)
        return EasyTierRoomListPage(
            rooms = payload.rooms.map { room ->
            EasyTierRoomListItem(
                roomId = room.roomId.trim(),
                ownerPlayerId = room.ownerPlayerId.trim(),
                ownerDisplayName = room.ownerDisplayName.trim(),
                mode = EasyTierNetworkMode.fromCloudControl(room.mode),
                allowNewJoins = room.allowNewJoins,
                closedAtMs = room.closedAtMs,
                memberCount = room.memberCount,
                onlineMemberCount = room.onlineMemberCount,
                roomState = room.roomState.trim(),
                lastSessionStartedAtMs = room.lastSessionStartedAtMs,
                updatedAtMs = room.updatedAtMs,
            )
            },
            nextOffset = payload.nextOffset?.takeIf { it >= 0 },
        )
    }

    private fun buildDeviceSummary(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val parts = listOf(
            manufacturer.takeIf { it.isNotEmpty() },
            model.takeIf { it.isNotEmpty() },
            "sdk${Build.VERSION.SDK_INT}",
        )
        return parts.joinToString(" ").trim().ifEmpty { "android" }
    }

    private fun apiUrl(
        baseUrl: String,
        vararg pathSegments: String,
        queryParameters: Map<String, String> = emptyMap(),
    ): HttpUrl {
        val builder = baseUrl.trim().removeSuffix("/").toHttpUrl().newBuilder()
        pathSegments.forEach { segment ->
            builder.addPathSegment(segment)
        }
        queryParameters.forEach { (name, value) ->
            builder.addQueryParameter(name, value)
        }
        return builder.build()
    }

    private fun Request.Builder.applyLanCredentials(
        sessionToken: String,
        ownerToken: String,
    ): Request.Builder = applyLanSessionToken(sessionToken).applyLanOwnerToken(ownerToken)

    private fun Request.Builder.applyLanSessionToken(sessionToken: String): Request.Builder = apply {
        sessionToken.trim().takeIf { it.isNotEmpty() }?.let { token ->
            header("Authorization", "Bearer $token")
        }
    }

    private fun Request.Builder.applyLanOwnerToken(ownerToken: String): Request.Builder = apply {
        ownerToken.trim().takeIf { it.isNotEmpty() }?.let { token ->
            header("X-Lan-Owner-Token", token)
        }
    }

    @Serializable
    private data class StartSessionRequest(
        val roomId: String,
        val playerId: String,
        val displayName: String,
        val clientVersion: String,
        val deviceSummary: String,
        val allowNewJoins: Boolean? = null,
    )

    @Serializable
    private data class StopSessionRequest(
        val sessionId: String,
    )

    @Serializable
    private data class ReportSessionRuntimeRequest(
        val sessionId: String,
        val assignedIpv4Cidr: String,
        val relayServerDescription: String = "",
    )

    @Serializable
    private data class UpdateRoomRequest(
        val action: String,
    )

    @Serializable
    private data class StartSessionResponse(
        val sessionId: String,
        val roomId: String,
        val mode: String = "room",
        val entryNodeUrl: String,
        val configServerUrl: String = "",
        val aclGroup: String = "",
        val networkSecret: String = "",
        val sessionToken: String = "",
        val ownerToken: String = "",
        @SerialName("expiresAt")
        val expiresAtEpochSeconds: Long? = null,
    )

    @Serializable
    private data class SessionStatusResponse(
        val sessionId: String,
        val roomId: String,
        val sessionState: String,
        val roomState: String,
        val peerCount: Int? = null,
        val assignedIpv4Cidr: String = "",
        val relayServerDescription: String = "",
    )

    @Serializable
    private data class RoomInfoResponse(
        val roomId: String,
        val ownerPlayerId: String,
        val ownerDisplayName: String = "",
        val mode: String = "room",
        val allowNewJoins: Boolean = false,
        val closedAtMs: Long = 0L,
        val memberCount: Int = 0,
        val members: List<RoomMemberResponse> = emptyList(),
    )

    @Serializable
    private data class RoomListResponse(
        val rooms: List<RoomListItemResponse> = emptyList(),
        val nextOffset: Int? = null,
    )

    @Serializable
    private data class RoomListItemResponse(
        val roomId: String,
        val ownerPlayerId: String,
        val ownerDisplayName: String = "",
        val mode: String = "room",
        val allowNewJoins: Boolean = false,
        val closedAtMs: Long = 0L,
        val memberCount: Int = 0,
        val onlineMemberCount: Int = 0,
        val roomState: String = "",
        val lastSessionStartedAtMs: Long = 0L,
        val updatedAtMs: Long = 0L,
    )

    @Serializable
    private data class RoomMemberResponse(
        val playerId: String,
        val displayName: String = "",
        val role: String = "",
        val online: Boolean = false,
        val assignedIpv4Cidr: String = "",
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        internal fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder().build()
    }
}
