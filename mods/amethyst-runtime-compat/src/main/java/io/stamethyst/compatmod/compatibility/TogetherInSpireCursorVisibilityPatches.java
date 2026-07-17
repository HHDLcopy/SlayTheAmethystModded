package io.stamethyst.compatmod.compatibility;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;

public final class TogetherInSpireCursorVisibilityPatches {
    private static final String CURSOR_CLASS = "spireTogether.ui.elements.presets.Cursor";
    private static final String CHAT_FLAG_CURSOR_TYPE_PATCH_CLASS =
        "spireTogether.chat.ChatTargeting$RenderPrefixPatcher";
    private static final String CHAT_FLAG_CURSOR_RENDER_PATCH_CLASS =
        "spireTogether.chat.ChatTargeting$RenderPatcher";

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

    @SpirePatch2(
        cls = CHAT_FLAG_CURSOR_TYPE_PATCH_CLASS,
        method = "Prefix",
        requiredModId = TogetherInSpireCompatRuntime.MOD_ID,
        optional = true
    )
    public static class ChatFlagCursorTypePatch {
        @SpirePrefixPatch
        public static SpireReturn<Void> Prefix() {
            return suppressLocalCursorInHybridMode();
        }
    }

    @SpirePatch2(
        cls = CHAT_FLAG_CURSOR_RENDER_PATCH_CLASS,
        method = "Postfix",
        requiredModId = TogetherInSpireCompatRuntime.MOD_ID,
        optional = true
    )
    public static class ChatFlagCursorRenderPatch {
        @SpirePrefixPatch
        public static SpireReturn<Void> Prefix() {
            return suppressLocalCursorInHybridMode();
        }
    }

    private static SpireReturn<Void> suppressLocalCursorInHybridMode() {
        if (TogetherInSpireCompatRuntime.isHybridInputMode()) {
            return SpireReturn.Return();
        }
        return SpireReturn.Continue();
    }
}
