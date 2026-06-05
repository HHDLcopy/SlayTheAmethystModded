package io.stamethyst.compatmod;

import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.saveAndContinue.SaveFile;

import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

public final class RelicEnterRoomRescuePatches {
    private RelicEnterRoomRescuePatches() {
    }

    @SpirePatch2(
        clz = AbstractDungeon.class,
        method = "nextRoomTransition",
        paramtypez = {SaveFile.class}
    )
    public static class AbstractDungeonNextRoomTransitionPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return new ExprEditor() {
                @Override
                public void edit(MethodCall call) throws CannotCompileException {
                    if (!AbstractRelic.class.getName().equals(call.getClassName())) {
                        return;
                    }
                    if (!"onEnterRoom".equals(call.getMethodName())) {
                        return;
                    }
                    call.replace(
                        "{ "
                            + RelicEnterRoomRescuePatches.class.getName()
                            + ".safeOnEnterRoom($0, $1); "
                            + "}"
                    );
                }
            };
        }
    }

    public static void safeOnEnterRoom(AbstractRelic relic, AbstractRoom room) {
        if (!CompatRuntimeState.isRelicEnterRoomRescueEnabled()) {
            relic.onEnterRoom(room);
            return;
        }
        if (relic == null) {
            RoomStateRescueNoticeBridge.notifyRescue(
                "relic_enter_room",
                "Skipped null relic onEnterRoom during room transition"
            );
            return;
        }
        try {
            relic.onEnterRoom(room);
        } catch (NullPointerException exception) {
            RoomStateRescueNoticeBridge.notifyRescue(
                "relic_enter_room",
                "Skipped relic onEnterRoom for "
                    + relic.relicId
                    + " after "
                    + RoomContextRescueRuntime.describeThrowable(exception)
            );
        }
    }
}
