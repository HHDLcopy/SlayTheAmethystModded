package io.stamethyst.compatmod.touch;

import io.stamethyst.compatmod.core.CompatRuntimeState;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.rooms.CampfireUI;
import com.megacrit.cardcrawl.rooms.RestRoom;
import com.megacrit.cardcrawl.ui.campfire.AbstractCampfireOption;

import java.util.Map;
import java.util.WeakHashMap;

import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.FieldAccess;
import javassist.expr.MethodCall;

public final class CampfireDesktopTouchConfirmPatches {
    private static final Map<CampfireUI, AbstractCampfireOption> PENDING_OPTIONS =
        new WeakHashMap<CampfireUI, AbstractCampfireOption>();

    private static AbstractCampfireOption suppressedOption;
    private static boolean executingConfirmedOption;
    private static boolean compatLogPrinted;

    private CampfireDesktopTouchConfirmPatches() {
    }

    @SpirePatch2(
        clz = AbstractCampfireOption.class,
        method = "update"
    )
    public static class AbstractCampfireOptionUpdatePatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return new ExprEditor() {
                @Override
                public void edit(MethodCall call) throws CannotCompileException {
                    if (!AbstractCampfireOption.class.getName().equals(call.getClassName())) {
                        return;
                    }
                    if (!"useOption".equals(call.getMethodName())) {
                        return;
                    }
                    call.replace(
                        "{ if (" + CampfireDesktopTouchConfirmPatches.class.getName()
                            + ".shouldProceedWithDesktopUseOption(this)) { $proceed($$); } }"
                    );
                }

                @Override
                public void edit(FieldAccess access) throws CannotCompileException {
                    if (!access.isWriter()) {
                        return;
                    }
                    if (!CampfireUI.class.getName().equals(access.getClassName())) {
                        return;
                    }
                    if (!"somethingSelected".equals(access.getFieldName())) {
                        return;
                    }
                    access.replace(
                        "{ if (" + CampfireDesktopTouchConfirmPatches.class.getName()
                            + ".shouldSetCampfireSomethingSelected(this, $1)) { $proceed($$); } }"
                    );
                }
            };
        }
    }

    @SpirePatch2(
        clz = CampfireUI.class,
        method = "update"
    )
    public static class CampfireUiUpdatePatch {
        @SpirePostfixPatch
        public static void Postfix(CampfireUI __instance) {
            updateConfirm(__instance);
            suppressedOption = null;
        }
    }

    @SpirePatch2(
        clz = CampfireUI.class,
        method = "render",
        paramtypez = {SpriteBatch.class}
    )
    public static class CampfireUiRenderPatch {
        @SpirePostfixPatch
        public static void Postfix(CampfireUI __instance, Object[] __args) {
            renderConfirm(__instance, (SpriteBatch) __args[0]);
        }
    }

    public static boolean shouldProceedWithDesktopUseOption(AbstractCampfireOption option) {
        if (executingConfirmedOption) {
            return true;
        }
        if (!isNativeTouchscreenCampfireConfirmActive()) {
            return true;
        }
        CampfireUI campfireUI = currentCampfireUi();
        if (campfireUI == null || option == null || campfireUI.somethingSelected || CampfireUI.hidden) {
            return true;
        }
        if (!option.usable) {
            return true;
        }

        suppressedOption = option;
        requestConfirmation(campfireUI, option);
        return false;
    }

    public static boolean shouldSetCampfireSomethingSelected(
        AbstractCampfireOption option,
        boolean value
    ) {
        if (executingConfirmedOption) {
            suppressedOption = null;
            return value;
        }
        if (!isNativeTouchscreenCampfireConfirmActive()) {
            return value;
        }
        if (option != null && option == suppressedOption) {
            suppressedOption = null;
            return false;
        }
        return value;
    }

    private static void requestConfirmation(CampfireUI campfireUI, AbstractCampfireOption option) {
        synchronized (PENDING_OPTIONS) {
            PENDING_OPTIONS.put(campfireUI, option);
        }
        campfireUI.touchOption = null;
        campfireUI.confirmButton.hideInstantly();
        campfireUI.confirmButton.isDisabled = false;
        campfireUI.confirmButton.hb.clicked = false;
        campfireUI.confirmButton.hb.clickStarted = false;
        campfireUI.confirmButton.show();
        logCompatOnce();
    }

    private static void updateConfirm(CampfireUI campfireUI) {
        if (!isNativeTouchscreenCampfireConfirmActive()) {
            clearConfirmation(campfireUI);
            return;
        }

        AbstractCampfireOption option = getPendingOption(campfireUI);
        if (option == null) {
            return;
        }
        if (campfireUI.somethingSelected || CampfireUI.hidden || !option.usable) {
            clearConfirmation(campfireUI);
            return;
        }

        campfireUI.confirmButton.update();
        if (campfireUI.confirmButton.hb.clicked) {
            campfireUI.confirmButton.hb.clicked = false;
            campfireUI.confirmButton.hb.clickStarted = false;
            executeConfirmedOption(campfireUI, option);
            return;
        }

        if (InputHelper.justReleasedClickLeft
            && !campfireUI.confirmButton.hb.hovered
            && !option.hb.hovered) {
            clearConfirmation(campfireUI);
        }
    }

    private static void renderConfirm(CampfireUI campfireUI, SpriteBatch spriteBatch) {
        if (!isNativeTouchscreenCampfireConfirmActive()
            || CampfireUI.hidden
            || getPendingOption(campfireUI) == null) {
            return;
        }
        campfireUI.confirmButton.render(spriteBatch);
    }

    private static void executeConfirmedOption(CampfireUI campfireUI, AbstractCampfireOption option) {
        clearPendingOption(campfireUI);
        campfireUI.touchOption = null;
        campfireUI.confirmButton.isDisabled = true;
        campfireUI.confirmButton.hide();

        executingConfirmedOption = true;
        suppressedOption = null;
        try {
            option.hb.clicked = true;
            option.hb.clickStarted = false;
            option.update();
        } finally {
            option.hb.clicked = false;
            executingConfirmedOption = false;
            suppressedOption = null;
        }
    }

    private static void clearConfirmation(CampfireUI campfireUI) {
        if (campfireUI == null) {
            return;
        }
        clearPendingOption(campfireUI);
        campfireUI.confirmButton.isDisabled = true;
        campfireUI.confirmButton.hide();
    }

    private static AbstractCampfireOption getPendingOption(CampfireUI campfireUI) {
        synchronized (PENDING_OPTIONS) {
            return PENDING_OPTIONS.get(campfireUI);
        }
    }

    private static void clearPendingOption(CampfireUI campfireUI) {
        synchronized (PENDING_OPTIONS) {
            PENDING_OPTIONS.remove(campfireUI);
        }
    }

    private static CampfireUI currentCampfireUi() {
        try {
            if (!(AbstractDungeon.getCurrRoom() instanceof RestRoom)) {
                return null;
            }
            return ((RestRoom) AbstractDungeon.getCurrRoom()).campfireUI;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isNativeTouchscreenCampfireConfirmActive() {
        return CompatRuntimeState.isCampfireDesktopTouchConfirmEnabled()
            && CompatRuntimeState.resolveVanillaAllowlistedTouchscreenFlag(Settings.isTouchScreen);
    }

    private static void logCompatOnce() {
        if (compatLogPrinted) {
            return;
        }
        compatLogPrinted = true;
        System.out.println(
            "[amethyst-runtime-compat] campfire native-touch confirmation uses desktop option semantics"
        );
    }
}
