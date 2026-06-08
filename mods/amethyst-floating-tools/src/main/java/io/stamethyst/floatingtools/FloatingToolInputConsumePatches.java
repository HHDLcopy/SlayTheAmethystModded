package io.stamethyst.floatingtools;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.helpers.input.InputHelper;

public class FloatingToolInputConsumePatches {
    @SpirePatch2(clz = InputHelper.class, method = "updateFirst")
    public static class ConsumeAfterInputUpdate {
        @SpirePostfixPatch
        public static void postfix() {
            AmethystFloatingTools.updateInputFromGame();
        }
    }
}
