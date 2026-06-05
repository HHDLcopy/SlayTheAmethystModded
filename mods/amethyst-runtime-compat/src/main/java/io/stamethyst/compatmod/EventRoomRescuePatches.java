package io.stamethyst.compatmod;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.random.Random;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.rooms.EventRoom;

public final class EventRoomRescuePatches {
    private EventRoomRescuePatches() {
    }

    @SpirePatch2(
        clz = EventRoom.class,
        method = "onPlayerEntry"
    )
    public static class EventRoomOnPlayerEntryPatch {
        @SpirePrefixPatch
        public static SpireReturn<Void> Prefix(EventRoom __instance) {
            if (!CompatRuntimeState.isEventRoomRescueEnabled()) {
                return SpireReturn.Continue();
            }

            try {
                AbstractDungeon.overlayMenu.proceedButton.hide();
                Random eventRngDuplicate = new Random(Settings.seed, AbstractDungeon.eventRng.counter);
                __instance.event = AbstractDungeon.generateEvent(eventRngDuplicate);
                if (__instance.event == null) {
                    throw new NullPointerException("AbstractDungeon.generateEvent returned null");
                }
                __instance.event.onEnterRoom();
                return SpireReturn.Return();
            } catch (RuntimeException exception) {
                if (!RoomContextRescueRuntime.isLikelyNullContextFailure(exception)) {
                    throw exception;
                }
                rescueEventRoom(
                    __instance,
                    "event_room_entry",
                    "EventRoom.onPlayerEntry rescued after "
                        + RoomContextRescueRuntime.describeThrowable(exception)
                );
                return SpireReturn.Return();
            }
        }
    }

    @SpirePatch2(
        clz = EventRoom.class,
        method = "update"
    )
    public static class EventRoomUpdatePatch {
        @SpirePrefixPatch
        public static SpireReturn<Void> Prefix(EventRoom __instance) {
            if (!CompatRuntimeState.isEventRoomRescueEnabled() || __instance.event != null) {
                return SpireReturn.Continue();
            }

            rescueEventRoom(
                __instance,
                "event_room_update_null_event",
                "EventRoom.update rescued because event was null"
            );
            return SpireReturn.Return();
        }
    }

    private static void rescueEventRoom(EventRoom room, String key, String detail) {
        room.event = null;
        room.phase = AbstractRoom.RoomPhase.COMPLETE;
        room.rewardPopOutTimer = 0.0f;
        RoomContextRescueRuntime.showProceedButtonIfPossible();
        RoomStateRescueNoticeBridge.notifyRescue(key, detail);
    }
}
