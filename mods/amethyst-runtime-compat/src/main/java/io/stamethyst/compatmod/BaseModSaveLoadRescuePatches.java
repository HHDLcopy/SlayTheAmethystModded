package io.stamethyst.compatmod;

import basemod.abstracts.CustomSavableRaw;

import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.google.gson.JsonElement;

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
                            + BaseModSaveLoadRescuePatches.class.getName()
                            + ".safeOnLoadRaw($0, $1); "
                            + "}"
                    );
                }
            };
        }
    }

    public static void safeOnLoadRaw(CustomSavableRaw saveable, JsonElement value) {
        if (!CompatRuntimeState.isBaseModSaveLoadRescueEnabled()) {
            saveable.onLoadRaw(value);
            return;
        }
        if (saveable == null) {
            RoomStateRescueNoticeBridge.notifyRescue(
                "basemod_save_load",
                "Skipped null CustomSavableRaw during BaseMod save load"
            );
            return;
        }
        try {
            saveable.onLoadRaw(value);
        } catch (NullPointerException exception) {
            RoomStateRescueNoticeBridge.notifyRescue(
                "basemod_save_load",
                "Skipped CustomSavableRaw.onLoadRaw for "
                    + saveable.getClass().getName()
                    + " after "
                    + RoomContextRescueRuntime.describeThrowable(exception)
            );
        }
    }
}
