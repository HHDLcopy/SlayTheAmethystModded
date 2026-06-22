package io.stamethyst.compatmod.rescue;

import io.stamethyst.compatmod.core.CompatRuntimeState;
import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.relics.AbstractRelic;
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
                            + "if ($0 == null && "
                            + CompatRuntimeState.class.getName()
                            + ".isRelicEnterRoomRescueEnabled()) { "
                            + RelicEnterRoomRescuePatches.class.getName()
                            + ".handleNullRelic(); "
                            + "} else { "
                            + "try { "
                            + "$proceed($$); "
                            + "} catch (java.lang.NullPointerException exception) { "
                            + RelicEnterRoomRescuePatches.class.getName()
                            + ".handleOnEnterRoomException($0, exception); "
                            + "} "
                            + "} "
                            + "}"
                    );
                }
            };
        }
    }

    public static void handleNullRelic() {
        RoomStateRescueNoticeBridge.notifyRescue(
            "relic_enter_room",
            "Skipped null relic onEnterRoom during room transition"
        );
    }

    public static void handleOnEnterRoomException(
        AbstractRelic relic,
        NullPointerException exception
    ) {
        if (!CompatRuntimeState.isRelicEnterRoomRescueEnabled()) {
            throw exception;
        }
        RoomStateRescueNoticeBridge.notifyRescue(
            "relic_enter_room",
            "Skipped relic onEnterRoom for "
                + describeRelic(relic)
                + " after "
                + RoomContextRescueRuntime.describeThrowable(exception)
        );
    }

    private static String describeRelic(AbstractRelic relic) {
        if (relic == null) {
            return "<null>";
        }
        if (relic.relicId != null) {
            return relic.relicId;
        }
        if (relic.name != null) {
            return relic.name;
        }
        return relic.getClass().getName();
    }
}
