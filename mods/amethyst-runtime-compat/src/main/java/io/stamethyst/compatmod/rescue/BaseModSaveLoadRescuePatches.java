package io.stamethyst.compatmod.rescue;

import io.stamethyst.compatmod.core.CompatRuntimeState;
import basemod.abstracts.CustomSavableRaw;

import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;

import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

public final class BaseModSaveLoadRescuePatches {
    private static final String LOAD_PLAYER_SAVES_CLASS =
        "basemod.patches.com.megacrit.cardcrawl.core.CardCrawlGame.LoadPlayerSaves";

    private BaseModSaveLoadRescuePatches() {
    }

    @SpirePatch2(
        cls = LOAD_PLAYER_SAVES_CLASS,
        method = "Postfix",
        optional = true
    )
    public static class LoadPlayerSavesPostfixPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return new ExprEditor() {
                @Override
                public void edit(MethodCall call) throws CannotCompileException {
                    if (!CustomSavableRaw.class.getName().equals(call.getClassName())) {
                        return;
                    }
                    if (!"onLoadRaw".equals(call.getMethodName())) {
                        return;
                    }
                    call.replace(
                        "{ "
                            + "if ($0 == null && "
                            + CompatRuntimeState.class.getName()
                            + ".isBaseModSaveLoadRescueEnabled()) { "
                            + BaseModSaveLoadRescuePatches.class.getName()
                            + ".handleNullSaveable(); "
                            + "} else { "
                            + "try { "
                            + "$proceed($$); "
                            + "} catch (java.lang.NullPointerException exception) { "
                            + BaseModSaveLoadRescuePatches.class.getName()
                            + ".handleOnLoadRawException($0, exception); "
                            + "} "
                            + "} "
                            + "}"
                    );
                }
            };
        }
    }

    public static void handleNullSaveable() {
        RoomStateRescueNoticeBridge.notifyRescue(
            "basemod_save_load",
            "Skipped null CustomSavableRaw during BaseMod save load"
        );
    }

    public static void handleOnLoadRawException(
        CustomSavableRaw saveable,
        NullPointerException exception
    ) {
        if (!CompatRuntimeState.isBaseModSaveLoadRescueEnabled()) {
            throw exception;
        }
        RoomStateRescueNoticeBridge.notifyRescue(
            "basemod_save_load",
            "Skipped CustomSavableRaw.onLoadRaw for "
                + describeSaveable(saveable)
                + " after "
                + RoomContextRescueRuntime.describeThrowable(exception)
        );
    }

    private static String describeSaveable(CustomSavableRaw saveable) {
        if (saveable == null) {
            return "<null>";
        }
        return saveable.getClass().getName();
    }
}
