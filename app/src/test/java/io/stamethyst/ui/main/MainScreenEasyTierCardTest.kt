package io.stamethyst.ui.main

import io.stamethyst.backend.easytier.EasyTierRoomMember
import org.junit.Assert.assertEquals
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
}
