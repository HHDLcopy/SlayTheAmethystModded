package io.stamethyst.compatmod;

import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.helpers.Hitbox;

import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.FieldAccess;

public final class TouchscreenTargetAssistPatches {
    private TouchscreenTargetAssistPatches() {
    }

    @SpirePatch2(
        clz = AbstractPlayer.class,
        method = "updateSingleTargetInput"
    )
    public static class AbstractPlayerUpdateSingleTargetInputPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return new ExprEditor() {
                @Override
                public void edit(FieldAccess access) throws CannotCompileException {
                    if (!access.isReader()) {
                        return;
                    }
                    if (!Hitbox.class.getName().equals(access.getClassName())) {
                        return;
                    }
                    if (!"hovered".equals(access.getFieldName())) {
                        return;
                    }
                    access.replace(
                        "{ $_ = "
                            + TouchscreenCardInputRuntime.class.getName()
                            + ".resolveSingleTargetMonsterHover($proceed(), $0); }"
                    );
                }
            };
        }
    }
}
