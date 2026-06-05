package io.stamethyst.compatmod;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.megacrit.cardcrawl.core.AbstractCreature;
import com.megacrit.cardcrawl.powers.AbstractPower;

import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

public final class PowerIconRenderRescuePatches {
    private PowerIconRenderRescuePatches() {
    }

    @SpirePatch2(
        clz = AbstractCreature.class,
        method = "renderPowerIcons",
        paramtypez = {SpriteBatch.class, float.class, float.class}
    )
    public static class AbstractCreatureRenderPowerIconsPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return new ExprEditor() {
                @Override
                public void edit(MethodCall call) throws CannotCompileException {
                    if (!AbstractPower.class.getName().equals(call.getClassName())) {
                        return;
                    }
                    if (!"renderIcons".equals(call.getMethodName())) {
                        return;
                    }
                    call.replace(
                        "{ "
                            + PowerIconRenderRescuePatches.class.getName()
                            + ".safeRenderIcons($0, $1, $2, $3, $4); "
                            + "}"
                    );
                }
            };
        }
    }

    public static void safeRenderIcons(
        AbstractPower power,
        SpriteBatch spriteBatch,
        float x,
        float y,
        Color color
    ) {
        if (!CompatRuntimeState.isPowerIconRenderRescueEnabled()) {
            power.renderIcons(spriteBatch, x, y, color);
            return;
        }
        try {
            power.renderIcons(spriteBatch, x, y, color);
        } catch (NullPointerException exception) {
            if (!isMissingIconResource(power)) {
                throw exception;
            }
            RoomStateRescueNoticeBridge.notifyRescue(
                "power_icon_render",
                "Skipped power icon render for "
                    + describePower(power)
                    + " because both img and region48 were missing after "
                    + RoomContextRescueRuntime.describeThrowable(exception)
            );
        }
    }

    private static boolean isMissingIconResource(AbstractPower power) {
        return power != null && power.img == null && power.region48 == null;
    }

    private static String describePower(AbstractPower power) {
        if (power == null) {
            return "<null>";
        }
        if (power.ID != null) {
            return power.ID;
        }
        if (power.name != null) {
            return power.name;
        }
        return power.getClass().getName();
    }
}

