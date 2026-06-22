package io.stamethyst.compatmod.touch;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.characters.AbstractPlayer;

public final class TouchscreenCardDragGesturePatches {
    private TouchscreenCardDragGesturePatches() {
    }

    @SpirePatch2(
        clz = AbstractPlayer.class,
        method = "clickAndDragCards"
    )
    public static class AbstractPlayerClickAndDragCardsPatch {
        @SpirePrefixPatch
        public static SpireReturn<Boolean> before(AbstractPlayer __instance) {
            TouchscreenCardInputRuntime.beforeClickAndDragCards(__instance);
            if (!TouchscreenCardInputRuntime.shouldCancelUnconfirmedDropPlay(__instance)) {
                return SpireReturn.Continue();
            }

            TouchscreenCardInputRuntime.cancelUnconfirmedDropPlay(__instance);
            return SpireReturn.Return(Boolean.TRUE);
        }

        @SpirePostfixPatch
        public static void after(AbstractPlayer __instance) {
            TouchscreenCardInputRuntime.afterClickAndDragCards(__instance);
        }
    }
}
