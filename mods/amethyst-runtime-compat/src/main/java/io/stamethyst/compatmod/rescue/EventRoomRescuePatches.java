package io.stamethyst.compatmod.rescue;

import io.stamethyst.compatmod.core.CompatRuntimeState;
import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.events.AbstractEvent;
import com.megacrit.cardcrawl.random.Random;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.rooms.EventRoom;

import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

public final class EventRoomRescuePatches {
    private static String pendingEventGenerationFailureDetail;

    private EventRoomRescuePatches() {
    }

    @SpirePatch2(
        clz = EventRoom.class,
        method = "onPlayerEntry"
    )
    public static class EventRoomOnPlayerEntryPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return new ExprEditor() {
                @Override
                public void edit(MethodCall call) throws CannotCompileException {
                    if (AbstractDungeon.class.getName().equals(call.getClassName())
                        && "generateEvent".equals(call.getMethodName())) {
                        call.replace(
                            "{ $_ = "
                                + EventRoomRescuePatches.class.getName()
                                + ".generateEventWithRescue($$); }"
                        );
                        return;
                    }

                    if (AbstractEvent.class.getName().equals(call.getClassName())
                        && "onEnterRoom".equals(call.getMethodName())) {
                        call.replace(
                            "{ "
                                + EventRoomRescuePatches.class.getName()
                                + ".enterEventOrRescue("
                                + "(com.megacrit.cardcrawl.rooms.EventRoom)this, "
                                + "(com.megacrit.cardcrawl.events.AbstractEvent)$0"
                                + "); }"
                        );
                    }
                }
            };
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

    public static AbstractEvent generateEventWithRescue(Random eventRng) {
        if (!CompatRuntimeState.isEventRoomRescueEnabled()) {
            return AbstractDungeon.generateEvent(eventRng);
        }

        try {
            AbstractEvent event = AbstractDungeon.generateEvent(eventRng);
            pendingEventGenerationFailureDetail = event == null
                ? "EventRoom.onPlayerEntry rescued because AbstractDungeon.generateEvent returned null"
                : null;
            return event;
        } catch (RuntimeException exception) {
            if (!RoomContextRescueRuntime.isLikelyNullContextFailure(exception)) {
                throw exception;
            }
            pendingEventGenerationFailureDetail = "EventRoom.onPlayerEntry rescued after "
                + RoomContextRescueRuntime.describeThrowable(exception);
            return null;
        }
    }

    public static void enterEventOrRescue(EventRoom room, AbstractEvent event) {
        if (!CompatRuntimeState.isEventRoomRescueEnabled()) {
            event.onEnterRoom();
            return;
        }

        if (event == null) {
            String detail = pendingEventGenerationFailureDetail;
            pendingEventGenerationFailureDetail = null;
            rescueEventRoom(
                room,
                "event_room_entry",
                detail != null
                    ? detail
                    : "EventRoom.onPlayerEntry rescued because event was null"
            );
            return;
        }

        pendingEventGenerationFailureDetail = null;
        try {
            event.onEnterRoom();
        } catch (RuntimeException exception) {
            if (!RoomContextRescueRuntime.isLikelyNullContextFailure(exception)) {
                throw exception;
            }
            rescueEventRoom(
                room,
                "event_room_entry",
                "EventRoom.onPlayerEntry rescued after "
                    + RoomContextRescueRuntime.describeThrowable(exception)
            );
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
