package io.stamethyst.compatmod.touch;

import com.badlogic.gdx.Input;
import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.megacrit.cardcrawl.characters.AbstractPlayer;

import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

public final class TouchscreenCardInputCursorPatches {
    private TouchscreenCardInputCursorPatches() {
    }

    @SpirePatch2(
        clz = AbstractPlayer.class,
        method = "clickAndDragCards"
    )
    public static class AbstractPlayerClickAndDragCardsPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return createCursorPositionEditor();
        }
    }

    @SpirePatch2(
        clz = AbstractPlayer.class,
        method = "updateSingleTargetInput"
    )
    public static class AbstractPlayerUpdateSingleTargetInputPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return createCursorPositionEditor();
        }
    }

    private static ExprEditor createCursorPositionEditor() {
        return new ExprEditor() {
            @Override
            public void edit(MethodCall call) throws CannotCompileException {
                if (!Input.class.getName().equals(call.getClassName())) {
                    return;
                }
                if (!"setCursorPosition".equals(call.getMethodName())) {
                    return;
                }
                call.replace(
                    "{ "
                        + TouchscreenCardInputRuntime.class.getName()
                        + ".setCombatTouchCursorPosition($0, $1, $2); "
                        + "}"
                );
            }
        };
    }
}
