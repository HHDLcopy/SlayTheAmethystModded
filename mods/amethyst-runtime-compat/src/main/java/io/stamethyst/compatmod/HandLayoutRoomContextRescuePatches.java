package io.stamethyst.compatmod;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.cards.CardGroup;

public final class HandLayoutRoomContextRescuePatches {
    private HandLayoutRoomContextRescuePatches() {
    }

    @SpirePatch2(
        clz = CardGroup.class,
        method = "refreshHandLayout"
    )
    public static class CardGroupRefreshHandLayoutPatch {
        @SpirePrefixPatch
        public static SpireReturn<Void> Prefix() {
            if (!CompatRuntimeState.isHandLayoutRoomContextRescueEnabled()
                || RoomContextRescueRuntime.hasCurrentRoom()) {
                return SpireReturn.Continue();
            }

            RoomStateRescueNoticeBridge.notifyRescue(
                "hand_layout_room_context",
                "Skipped CardGroup.refreshHandLayout because the current room was unavailable"
            );
            return SpireReturn.Return();
        }
    }
}
