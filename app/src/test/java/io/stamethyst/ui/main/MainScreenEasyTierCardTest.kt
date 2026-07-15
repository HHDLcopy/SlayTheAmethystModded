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
    fun connectionAction_isAlwaysVisibleExceptForOwner() {
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
}
