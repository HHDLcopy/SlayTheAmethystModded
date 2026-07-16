package io.stamethyst.compatmod.compatibility;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;

public final class TogetherInSpireCursorVisibilityPatches {
    private static final String CURSOR_CLASS = "spireTogether.ui.elements.presets.Cursor";

    private TogetherInSpireCursorVisibilityPatches() {
    }

    @SpirePatch2(
        cls = CURSOR_CLASS,
        method = "render",
        requiredModId = TogetherInSpireCompatRuntime.MOD_ID,
        optional = true
    )
    public static class LocalCursorRenderPatch {
        @SpirePrefixPatch
        public static SpireReturn<Void> Prefix(Object[] __args) {
            if (!TogetherInSpireCompatRuntime.isHybridInputMode()) {
                return SpireReturn.Continue();
            }
            if (__args == null || __args.length == 0) {
                return SpireReturn.Continue();
            }
            Object selfCursor = __args[__args.length - 1];
            if (Boolean.TRUE.equals(selfCursor)) {
                return SpireReturn.Return();
            }
            return SpireReturn.Continue();
        }
    }
}
