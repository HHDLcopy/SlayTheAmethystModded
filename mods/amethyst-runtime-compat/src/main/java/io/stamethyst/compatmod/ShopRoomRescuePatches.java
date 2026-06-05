package io.stamethyst.compatmod;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.rooms.ShopRoom;
import com.megacrit.cardcrawl.shop.Merchant;

public final class ShopRoomRescuePatches {
    private ShopRoomRescuePatches() {
    }

    @SpirePatch2(
        clz = ShopRoom.class,
        method = "onPlayerEntry"
    )
    public static class ShopRoomOnPlayerEntryPatch {
        @SpirePrefixPatch
        public static SpireReturn<Void> Prefix(ShopRoom __instance) {
            if (!CompatRuntimeState.isShopRoomRescueEnabled()) {
                return SpireReturn.Continue();
            }

            try {
                if (!AbstractDungeon.id.equals("TheEnding")) {
                    __instance.playBGM("SHOP");
                }
                AbstractDungeon.overlayMenu.proceedButton.setLabel(ShopRoom.TEXT[0]);
                __instance.setMerchant(new Merchant());
                return SpireReturn.Return();
            } catch (RuntimeException exception) {
                if (!RoomContextRescueRuntime.isLikelyNullContextFailure(exception)) {
                    throw exception;
                }
                __instance.setMerchant(null);
                __instance.phase = AbstractRoom.RoomPhase.COMPLETE;
                __instance.rewardPopOutTimer = 0.0f;
                RoomContextRescueRuntime.showProceedButtonIfPossible();
                RoomStateRescueNoticeBridge.notifyRescue(
                    "shop_room_merchant",
                    "ShopRoom.onPlayerEntry skipped Merchant after "
                        + RoomContextRescueRuntime.describeThrowable(exception)
                );
                return SpireReturn.Return();
            }
        }
    }
}
