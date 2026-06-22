package io.stamethyst.compatmod.rescue;

import io.stamethyst.compatmod.core.CompatRuntimeState;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.rooms.MonsterRoom;

import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

public final class NonCombatPlayerRenderRescuePatches {
    private NonCombatPlayerRenderRescuePatches() {
    }

    @SpirePatch2(
        clz = AbstractRoom.class,
        method = "render",
        paramtypez = {SpriteBatch.class}
    )
    public static class AbstractRoomRenderPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return new ExprEditor() {
                @Override
                public void edit(MethodCall call) throws CannotCompileException {
                    if (!AbstractPlayer.class.getName().equals(call.getClassName())) {
                        return;
                    }
                    if (!"render".equals(call.getMethodName())) {
                        return;
                    }
                    call.replace(
                        "{ "
                            + "try { "
                            + "$proceed($$); "
                            + "} catch (java.lang.NullPointerException exception) { "
                            + NonCombatPlayerRenderRescuePatches.class.getName()
                            + ".handleRenderPlayerException(this, exception); "
                            + "} "
                            + "}"
                    );
                }
            };
        }
    }

    public static boolean shouldRescuePlayerRender(AbstractRoom room) {
        return CompatRuntimeState.isNonCombatPlayerRenderRescueEnabled()
            && !isCombatRoom(room);
    }

    public static void handleRenderPlayerException(
        AbstractRoom room,
        NullPointerException exception
    ) {
        if (!shouldRescuePlayerRender(room)) {
            throw exception;
        }
        RoomStateRescueNoticeBridge.notifyRescue(
            "non_combat_player_render",
            "Skipped non-combat player render in "
                + describeRoom(room)
                + " after "
                + RoomContextRescueRuntime.describeThrowable(exception)
        );
    }

    public static boolean isCombatRoom(AbstractRoom room) {
        if (room instanceof MonsterRoom) {
            return true;
        }
        return room != null && room.phase == AbstractRoom.RoomPhase.COMBAT;
    }

    private static String describeRoom(AbstractRoom room) {
        if (room == null) {
            return "<null>";
        }
        String phase = room.phase == null ? "<null>" : room.phase.name();
        return room.getClass().getName() + " phase=" + phase;
    }
}
