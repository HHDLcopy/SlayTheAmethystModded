package io.stamethyst.compatmod;

import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.cards.CardGroup;
import com.megacrit.cardcrawl.characters.AbstractPlayer;

import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

public final class TouchscreenIdleCardHoverPatches {
    private TouchscreenIdleCardHoverPatches() {
    }

    @SpirePatch2(
        clz = AbstractPlayer.class,
        method = "updateInput"
    )
    public static class AbstractPlayerUpdateInputPatch {
        @SpirePrefixPatch
        public static void Prefix(AbstractPlayer __instance) {
            TouchscreenCardInputRuntime.clearIdleCardHoverBeforeUpdate(__instance);
        }

        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return new ExprEditor() {
                @Override
                public void edit(MethodCall call) throws CannotCompileException {
                    if (!CardGroup.class.getName().equals(call.getClassName())) {
                        return;
                    }
                    if (!"getHoveredCard".equals(call.getMethodName())) {
                        return;
                    }
                    call.replace(
                        "{ $_ = "
                            + TouchscreenCardInputRuntime.class.getName()
                            + ".getHoveredCardForNativeTouch($0); }"
                    );
                }
            };
        }
    }
}
