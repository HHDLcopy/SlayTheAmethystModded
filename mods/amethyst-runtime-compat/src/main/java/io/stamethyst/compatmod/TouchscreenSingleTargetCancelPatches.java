package io.stamethyst.compatmod;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.helpers.input.InputHelper;

public final class TouchscreenSingleTargetCancelPatches {
    private static boolean blankTapCancelLogged;
    private static boolean blankTouchscreenPlayCancelLogged;
    private static boolean blankReleaseCancelLogged;

    private TouchscreenSingleTargetCancelPatches() {
    }

    @SpirePatch2(
        clz = AbstractPlayer.class,
        method = "clickAndDragCards"
    )
    public static class AbstractPlayerClickAndDragCardsPatch {
        @SpirePrefixPatch
        public static SpireReturn<Boolean> before(AbstractPlayer __instance) {
            if (!shouldCancelBlankTargetTap(__instance)) {
                return SpireReturn.Continue();
            }

            cancelSelectedCard(
                __instance,
                InputHelper.justReleasedClickLeft ? "blank touchscreen play release" : "blank tap",
                true
            );
            return SpireReturn.Return(Boolean.TRUE);
        }
    }

    @SpirePatch2(
        clz = AbstractPlayer.class,
        method = "updateSingleTargetInput"
    )
    public static class AbstractPlayerUpdateSingleTargetInputPatch {
        @SpirePrefixPatch
        public static SpireReturn<Void> before(AbstractPlayer __instance) {
            if (!shouldCancelBlankSingleTargetClick(__instance)) {
                return SpireReturn.Continue();
            }

            cancelSelectedCard(__instance, "blank click", true);
            return SpireReturn.Return(null);
        }

        @SpirePostfixPatch
        public static void after(AbstractPlayer __instance) {
            if (shouldCancelBlankSingleTargetRelease(__instance)) {
                cancelSelectedCard(__instance, "blank release", false);
            }
        }
    }

    private static boolean shouldCancelBlankTargetTap(AbstractPlayer player) {
        if (!TouchscreenCardInputRuntime.isNativeTouchscreenCardInputActive()) {
            return false;
        }
        if (player == null || player.hoveredCard == null) {
            return false;
        }
        if (!player.isDraggingCard) {
            return false;
        }
        if (!player.isHoveringDropZone) {
            return false;
        }
        if (!InputHelper.justClickedLeft && !InputHelper.justReleasedClickLeft) {
            return false;
        }
        if (!TouchscreenCardInputRuntime.isTargetedCard(player.hoveredCard)) {
            return false;
        }
        return !TouchscreenCardInputRuntime.isHoveringOrNearLiveMonster();
    }

    private static boolean shouldCancelBlankSingleTargetClick(AbstractPlayer player) {
        if (!TouchscreenCardInputRuntime.isNativeTouchscreenCardInputActive()) {
            return false;
        }
        if (player == null || !player.inSingleTargetMode || player.hoveredCard == null) {
            return false;
        }
        if (!InputHelper.justClickedLeft) {
            return false;
        }
        return !TouchscreenCardInputRuntime.isHoveringOrNearLiveMonster();
    }

    private static boolean shouldCancelBlankSingleTargetRelease(AbstractPlayer player) {
        if (!TouchscreenCardInputRuntime.isNativeTouchscreenCardInputActive()) {
            return false;
        }
        if (player == null || !player.inSingleTargetMode || player.hoveredCard == null) {
            return false;
        }
        return InputHelper.justReleasedClickLeft;
    }

    private static void cancelSelectedCard(AbstractPlayer player, String reason, boolean playCancelSound) {
        if (playCancelSound) {
            CardCrawlGame.sound.play("UI_CLICK_2");
        }
        TouchscreenCardInputRuntime.releaseSelectedCard(player);
        logCancelOnce(reason);
    }

    private static void logCancelOnce(String reason) {
        if ("blank tap".equals(reason)) {
            if (blankTapCancelLogged) {
                return;
            }
            blankTapCancelLogged = true;
        } else if ("blank touchscreen play release".equals(reason)) {
            if (blankTouchscreenPlayCancelLogged) {
                return;
            }
            blankTouchscreenPlayCancelLogged = true;
        } else {
            if (blankReleaseCancelLogged) {
                return;
            }
            blankReleaseCancelLogged = true;
        }
        System.out.println(
            "[amethyst-runtime-compat] touchscreen targeted-card cancel applied: " + reason
        );
    }
}
