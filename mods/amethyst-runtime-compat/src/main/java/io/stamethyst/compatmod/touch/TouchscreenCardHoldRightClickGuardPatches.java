package io.stamethyst.compatmod.touch;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.characters.AbstractPlayer;

public final class TouchscreenCardHoldRightClickGuardPatches {
    private TouchscreenCardHoldRightClickGuardPatches() {
    }

    @SpirePatch2(
        clz = AbstractPlayer.class,
        method = "updateSingleTargetInput"
    )
    public static class AbstractPlayerUpdateSingleTargetInputPatch {
        @SpirePrefixPatch
        public static void before(AbstractPlayer __instance) {
            TouchscreenCardInputRuntime.refreshSelectedCardHoldStateForAndroidBridge(__instance);
        }

        @SpirePostfixPatch
        public static void after(AbstractPlayer __instance) {
            TouchscreenCardInputRuntime.refreshSelectedCardHoldStateForAndroidBridge(__instance);
        }
    }
}
