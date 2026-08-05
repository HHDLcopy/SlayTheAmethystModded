package io.stamethyst.ui.main

import io.stamethyst.backend.easytier.EasyTierRoomMember
import io.stamethyst.backend.easytier.EasyTierNetworkMode
import io.stamethyst.backend.easytier.EasyTierRoomInfo
import io.stamethyst.backend.easytier.EasyTierRoomListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenEasyTierCardTest {
    @Test
    fun onlineLobbyCompatibility_usesLauncherUpdateVersionOrdering() {
        assertTrue(
            shouldRequireLauncherUpdateForOnlineLobby(
                currentVersion = "1.5.1-dev1",
                minimumCompatibleVersion = "1.5.1",
            )
        )
        assertFalse(
            shouldRequireLauncherUpdateForOnlineLobby(
                currentVersion = "1.5.1",
                minimumCompatibleVersion = "1.5.1",
            )
        )
        assertFalse(
            shouldRequireLauncherUpdateForOnlineLobby(
                currentVersion = "1.5.1-hotfix1",
                minimumCompatibleVersion = "1.5.1",
            )
        )
        assertFalse(
            shouldRequireLauncherUpdateForOnlineLobby(
                currentVersion = "1.0.0",
                minimumCompatibleVersion = "",
            )
        )
    }

    @Test
    fun initialUiState_mountsEasyTierCardBeforeInitializationCompletes() {
        val state = MainScreenViewModel.UiState()

        assertTrue(state.initializing)
        assertTrue(state.easyTierIndicator.visible)
        assertEquals(
            MainScreenViewModel.EasyTierIndicatorState.IDLE,
            state.easyTierIndicator.state,
        )
    }

    @Test
    fun resolveEasyTierRoomMemberIpv4Cidr_usesLocalAddressWhenServerOmitsCurrentPlayer() {
        val currentPlayer = EasyTierRoomMember(
            playerId = "player-1",
            displayName = "Player",
            role = "member",
            online = true,
        )

        assertEquals(
            "10.126.126.1/24",
            resolveEasyTierRoomMemberIpv4Cidr(
                member = currentPlayer,
                currentPlayerId = "player-1",
                localIpv4Cidr = "10.126.126.1/24",
            ),
        )
        assertEquals(
            "",
            resolveEasyTierRoomMemberIpv4Cidr(
                member = currentPlayer.copy(playerId = "player-2"),
                currentPlayerId = "player-1",
                localIpv4Cidr = "10.126.126.1/24",
            ),
        )
    }

    @Test
    fun isEasyTierRoomJoined_requiresActiveConnectionStateForSelectedRoom() {
        val active = MainScreenViewModel.EasyTierIndicatorUi(
            state = MainScreenViewModel.EasyTierIndicatorState.CONNECTED,
            roomId = "room-1",
        )

        assertTrue(isEasyTierRoomJoined(active, "room-1"))
        assertEquals(
            false,
            isEasyTierRoomJoined(
                active.copy(state = MainScreenViewModel.EasyTierIndicatorState.DISCONNECTED),
                "room-1",
            ),
        )
        assertEquals(false, isEasyTierRoomJoined(active, "room-2"))
    }

    @Test
    fun easyTierCreateRoomProgressMessageResId_tracksConnectionPhases() {
        assertEquals(
            io.stamethyst.R.string.main_easytier_create_room_progress_creating,
            easyTierCreateRoomProgressMessageResId(MainScreenViewModel.EasyTierIndicatorState.IDLE),
        )
        assertEquals(
            io.stamethyst.R.string.main_easytier_create_room_progress_joining,
            easyTierCreateRoomProgressMessageResId(MainScreenViewModel.EasyTierIndicatorState.CONNECTING),
        )
        assertEquals(
            io.stamethyst.R.string.main_easytier_create_room_progress_starting,
            easyTierCreateRoomProgressMessageResId(MainScreenViewModel.EasyTierIndicatorState.SESSION_READY),
        )
    }

    @Test
    fun easyTierRoomContentMode_mapsPageRefreshAndMembershipStates() {
        assertEquals(
            EasyTierRoomContentMode.Create,
            easyTierRoomContentMode(
                page = EasyTierRoomSheetPage.Create,
                panelMode = EasyTierRoomPanelMode.JoinedOwner,
            ),
        )
        assertEquals(
            EasyTierRoomContentMode.Tutorial,
            easyTierRoomContentMode(
                page = EasyTierRoomSheetPage.Tutorial,
                panelMode = EasyTierRoomPanelMode.Unjoined,
                roomsLoading = true,
            ),
        )
        assertEquals(
            EasyTierRoomContentMode.MemberMods,
            easyTierRoomContentMode(
                page = EasyTierRoomSheetPage.MemberMods,
                panelMode = EasyTierRoomPanelMode.JoinedMember,
                roomsLoading = true,
            ),
        )
        assertEquals(
            EasyTierRoomContentMode.Unjoined,
            easyTierRoomContentMode(
                page = EasyTierRoomSheetPage.Rooms,
                panelMode = EasyTierRoomPanelMode.Unjoined,
            ),
        )
        assertEquals(
            EasyTierRoomContentMode.Loading,
            easyTierRoomContentMode(
                page = EasyTierRoomSheetPage.Rooms,
                panelMode = EasyTierRoomPanelMode.Unjoined,
                roomsLoading = true,
            ),
        )
        assertEquals(
            EasyTierRoomContentMode.JoinedMember,
            easyTierRoomContentMode(
                page = EasyTierRoomSheetPage.Rooms,
                panelMode = EasyTierRoomPanelMode.JoinedMember,
            ),
        )
        assertEquals(
            EasyTierRoomContentMode.JoinedOwner,
            easyTierRoomContentMode(
                page = EasyTierRoomSheetPage.Rooms,
                panelMode = EasyTierRoomPanelMode.JoinedOwner,
            ),
        )
    }

    @Test
    fun connectionAction_requiresSelectedRoomAndHidesForOwner() {
        assertFalse(
            shouldShowEasyTierConnectionAction(
                panelMode = EasyTierRoomPanelMode.JoinedOwner,
            )
        )
        assertTrue(
            shouldShowEasyTierConnectionAction(
                panelMode = EasyTierRoomPanelMode.JoinedMember,
            )
        )
        assertTrue(
            shouldShowEasyTierConnectionAction(
                panelMode = EasyTierRoomPanelMode.Unjoined,
            )
        )
        assertFalse(
            shouldShowEasyTierConnectionAction(
                panelMode = EasyTierRoomPanelMode.Unjoined,
                hasSelectedRoom = false,
            )
        )
    }

    @Test
    fun disconnectAction_isEnabledOnlyAfterConnectionCompletes() {
        assertFalse(
            isEasyTierDisconnectActionEnabled(MainScreenViewModel.EasyTierIndicatorState.CONNECTING)
        )
        assertFalse(
            isEasyTierDisconnectActionEnabled(MainScreenViewModel.EasyTierIndicatorState.SESSION_READY)
        )
        assertTrue(
            isEasyTierDisconnectActionEnabled(MainScreenViewModel.EasyTierIndicatorState.CONNECTED)
        )
        assertFalse(
            isEasyTierDisconnectActionEnabled(MainScreenViewModel.EasyTierIndicatorState.RECONNECTING)
        )
        assertFalse(
            isEasyTierDisconnectActionEnabled(MainScreenViewModel.EasyTierIndicatorState.DISCONNECTING)
        )
    }

    @Test
    fun canSelectEasyTierRoom_blocksLockedMembersButAllowsTheOwner() {
        val lockedRoom = EasyTierRoomListItem(
            roomId = "room-1",
            ownerPlayerId = "owner-1",
            ownerDisplayName = "Owner",
            mode = EasyTierNetworkMode.Room,
            allowNewJoins = false,
            memberCount = 1,
        )

        assertFalse(canSelectEasyTierRoom(lockedRoom, "member-1"))
        assertTrue(canSelectEasyTierRoom(lockedRoom, "owner-1"))
        assertFalse(canSelectEasyTierRoom(lockedRoom.copy(closedAtMs = 42L), "owner-1"))
    }

    @Test
    fun canConnectEasyTierRoom_waitsForFreshDetailsAndAllowsLockedOwner() {
        val lockedRoom = EasyTierRoomInfo(
            roomId = "room-1",
            ownerPlayerId = "owner-1",
            ownerDisplayName = "Owner",
            mode = EasyTierNetworkMode.Room,
            allowNewJoins = false,
            memberCount = 1,
        )

        assertFalse(
            canConnectEasyTierRoom(
                room = lockedRoom,
                currentPlayerId = "member-1",
                refreshingRoomInfo = false,
                creating = false,
                mutating = false,
            )
        )
        assertTrue(
            canConnectEasyTierRoom(
                room = lockedRoom,
                currentPlayerId = "owner-1",
                refreshingRoomInfo = false,
                creating = false,
                mutating = false,
            )
        )
        assertFalse(
            canConnectEasyTierRoom(
                room = lockedRoom.copy(allowNewJoins = true),
                currentPlayerId = "member-1",
                refreshingRoomInfo = true,
                creating = false,
                mutating = false,
            )
        )
    }

    @Test
    fun easyTierSheetBackTarget_unwindsOneLevelAtATime() {
        assertEquals(
            EasyTierSheetBackTarget.None,
            easyTierSheetBackTarget(
                page = EasyTierRoomSheetPage.Rooms,
                workshopDetailVisible = false,
                creating = false,
            )
        )
        assertEquals(
            EasyTierSheetBackTarget.Rooms,
            easyTierSheetBackTarget(
                page = EasyTierRoomSheetPage.Create,
                workshopDetailVisible = false,
                creating = false,
            )
        )
        assertEquals(
            EasyTierSheetBackTarget.Rooms,
            easyTierSheetBackTarget(
                page = EasyTierRoomSheetPage.Tutorial,
                workshopDetailVisible = false,
                creating = false,
            )
        )
        assertEquals(
            EasyTierSheetBackTarget.MemberMods,
            easyTierSheetBackTarget(
                page = EasyTierRoomSheetPage.MemberMods,
                workshopDetailVisible = false,
                creating = false,
            )
        )
        // The workshop detail overlay sits on top of the member mods page.
        assertEquals(
            EasyTierSheetBackTarget.WorkshopDetail,
            easyTierSheetBackTarget(
                page = EasyTierRoomSheetPage.MemberMods,
                workshopDetailVisible = true,
                creating = false,
            )
        )
    }

    @Test
    fun easyTierSheetBackTarget_staysDisabledWhileCreatingButKeepsWorkshopDetail() {
        assertEquals(
            EasyTierSheetBackTarget.None,
            easyTierSheetBackTarget(
                page = EasyTierRoomSheetPage.Create,
                workshopDetailVisible = false,
                creating = true,
            )
        )
        assertEquals(
            EasyTierSheetBackTarget.WorkshopDetail,
            easyTierSheetBackTarget(
                page = EasyTierRoomSheetPage.MemberMods,
                workshopDetailVisible = true,
                creating = true,
            )
        )
    }

    @Test
    fun sortEasyTierRooms_ranksOwnRoomsThenJoinableThenBusiest() {
        val rooms = listOf(
            roomListItem(roomId = "quiet-open", onlineMemberCount = 1),
            roomListItem(roomId = "locked-busy", allowNewJoins = false, onlineMemberCount = 9),
            roomListItem(roomId = "busy-open", onlineMemberCount = 5),
            roomListItem(roomId = "mine", ownerPlayerId = "me", allowNewJoins = false),
        )

        val sorted = sortEasyTierRooms(rooms = rooms, currentPlayerId = "me")

        assertEquals(
            listOf("mine", "busy-open", "quiet-open", "locked-busy"),
            sorted.map { it.roomId },
        )
    }

    @Test
    fun sortEasyTierRooms_fallsBackToRoomIdSoAutoRefreshDoesNotReshuffle() {
        val rooms = listOf(
            roomListItem(roomId = "charlie", onlineMemberCount = 2, memberCount = 3),
            roomListItem(roomId = "alpha", onlineMemberCount = 2, memberCount = 3),
            roomListItem(roomId = "bravo", onlineMemberCount = 2, memberCount = 3),
        )

        val sorted = sortEasyTierRooms(rooms = rooms, currentPlayerId = "me")

        assertEquals(listOf("alpha", "bravo", "charlie"), sorted.map { it.roomId })
        // Re-sorting the already sorted list must not move anything.
        assertEquals(
            sorted.map { it.roomId },
            sortEasyTierRooms(rooms = sorted, currentPlayerId = "me").map { it.roomId },
        )
    }

    @Test
    fun filterEasyTierRooms_matchesRoomIdOwnerNameAndOwnerId() {
        val rooms = listOf(
            roomListItem(roomId = "dragon-den", ownerPlayerId = "p-1", ownerDisplayName = "Ada"),
            roomListItem(roomId = "quiet-room", ownerPlayerId = "p-2", ownerDisplayName = "Bob"),
        )

        val byRoomId = filterEasyTierRooms(rooms, "DRAGON", joinableOnly = false, currentPlayerId = "me")
        assertEquals(listOf("dragon-den"), byRoomId.map { it.roomId })

        val byOwnerName = filterEasyTierRooms(rooms, "bob", joinableOnly = false, currentPlayerId = "me")
        assertEquals(listOf("quiet-room"), byOwnerName.map { it.roomId })

        val byOwnerId = filterEasyTierRooms(rooms, "p-1", joinableOnly = false, currentPlayerId = "me")
        assertEquals(listOf("dragon-den"), byOwnerId.map { it.roomId })

        val blankQueryKeepsEverything =
            filterEasyTierRooms(rooms, "   ", joinableOnly = false, currentPlayerId = "me")
        assertEquals(rooms.size, blankQueryKeepsEverything.size)
    }

    @Test
    fun filterEasyTierRooms_joinableOnlyStillKeepsTheOwnersLockedRoom() {
        val rooms = listOf(
            roomListItem(roomId = "mine-locked", ownerPlayerId = "me", allowNewJoins = false),
            roomListItem(roomId = "other-locked", ownerPlayerId = "other", allowNewJoins = false),
            roomListItem(roomId = "other-open", ownerPlayerId = "other"),
        )

        val filtered = filterEasyTierRooms(rooms, "", joinableOnly = true, currentPlayerId = "me")

        assertEquals(listOf("mine-locked", "other-open"), filtered.map { it.roomId })
    }

    @Test
    fun isEasyTierRoomListFilteredEmpty_separatesFilterMissesFromAnEmptyBackend() {
        assertTrue(isEasyTierRoomListFilteredEmpty(totalRoomCount = 4, visibleRoomCount = 0))
        assertFalse(isEasyTierRoomListFilteredEmpty(totalRoomCount = 0, visibleRoomCount = 0))
        assertFalse(isEasyTierRoomListFilteredEmpty(totalRoomCount = 4, visibleRoomCount = 2))
    }

    @Test
    fun easyTierRoomsHeaderSummary_reportsRoomContextInsteadOfConnectionStatus() {
        assertEquals(
            EasyTierRoomsHeaderSummary.SelectedRoom,
            easyTierRoomsHeaderSummary(selectedRoomId = "dragon-den"),
        )
        assertEquals(
            EasyTierRoomsHeaderSummary.RoomCount,
            easyTierRoomsHeaderSummary(selectedRoomId = "   "),
        )
    }

    private fun roomListItem(
        roomId: String,
        ownerPlayerId: String = "owner-x",
        ownerDisplayName: String = "Owner X",
        allowNewJoins: Boolean = true,
        onlineMemberCount: Int = 0,
        memberCount: Int = 1,
    ) = EasyTierRoomListItem(
        roomId = roomId,
        ownerPlayerId = ownerPlayerId,
        ownerDisplayName = ownerDisplayName,
        mode = EasyTierNetworkMode.Room,
        allowNewJoins = allowNewJoins,
        memberCount = memberCount,
        onlineMemberCount = onlineMemberCount,
    )
}
