package io.stamethyst.compatmod.rescue;

import io.stamethyst.compatmod.core.CompatRuntimeState;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.megacrit.cardcrawl.monsters.AbstractMonster;

import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

public final class BaseModCustomMonsterRenderRescuePatches {
    private static final String CUSTOM_MONSTER_CLASS = "basemod.abstracts.CustomMonster";
    private static final String TEXTURE_DRAW_SIGNATURE =
        "(Lcom/badlogic/gdx/graphics/Texture;FFFFIIIIZZ)V";

    private BaseModCustomMonsterRenderRescuePatches() {
    }

    @SpirePatch2(
        cls = CUSTOM_MONSTER_CLASS,
        method = "render",
        paramtypez = {SpriteBatch.class},
        optional = true,
        requiredModId = "basemod"
    )
    public static class CustomMonsterRenderPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return new ExprEditor() {
                @Override
                public void edit(MethodCall call) throws CannotCompileException {
                    if (Texture.class.getName().equals(call.getClassName())) {
                        if ("getWidth".equals(call.getMethodName())) {
                            call.replace(
                                "{ "
                                    + "if ($0 == null && "
                                    + CompatRuntimeState.class.getName()
                                    + ".isBaseModCustomMonsterRenderRescueEnabled()) { "
                                    + "$_ = "
                                    + BaseModCustomMonsterRenderRescuePatches.class.getName()
                                    + ".handleMissingTextureWidth(this); "
                                    + "} else { "
                                    + "$_ = $proceed($$); "
                                    + "} "
                                    + "}"
                            );
                            return;
                        }
                        if ("getHeight".equals(call.getMethodName())) {
                            call.replace(
                                "{ "
                                    + "if ($0 == null && "
                                    + CompatRuntimeState.class.getName()
                                    + ".isBaseModCustomMonsterRenderRescueEnabled()) { "
                                    + "$_ = "
                                    + BaseModCustomMonsterRenderRescuePatches.class.getName()
                                    + ".handleMissingTextureHeight(this); "
                                    + "} else { "
                                    + "$_ = $proceed($$); "
                                    + "} "
                                    + "}"
                            );
                            return;
                        }
                    }
                    if (!SpriteBatch.class.getName().equals(call.getClassName())) {
                        return;
                    }
                    if (!"draw".equals(call.getMethodName())) {
                        return;
                    }
                    if (!TEXTURE_DRAW_SIGNATURE.equals(call.getSignature())) {
                        return;
                    }
                    call.replace(
                        "{ "
                            + "if ($1 == null && "
                            + CompatRuntimeState.class.getName()
                            + ".isBaseModCustomMonsterRenderRescueEnabled()) { "
                            + BaseModCustomMonsterRenderRescuePatches.class.getName()
                            + ".handleMissingTextureDraw(this); "
                            + "} else { "
                            + "$proceed($$); "
                            + "} "
                            + "}"
                    );
                }
            };
        }
    }

    public static int handleMissingTextureWidth(AbstractMonster monster) {
        notifyMissingTexture(monster);
        return 0;
    }

    public static int handleMissingTextureHeight(AbstractMonster monster) {
        notifyMissingTexture(monster);
        return 0;
    }

    public static void handleMissingTextureDraw(AbstractMonster monster) {
        notifyMissingTexture(monster);
    }

    private static void notifyMissingTexture(AbstractMonster monster) {
        RoomStateRescueNoticeBridge.notifyRescue(
            "basemod_custom_monster_render",
            "Skipped missing BaseMod CustomMonster image render for "
                + describeMonster(monster)
        );
    }

    private static String describeMonster(AbstractMonster monster) {
        if (monster == null) {
            return "<null>";
        }
        if (monster.id != null) {
            return monster.id;
        }
        if (monster.name != null) {
            return monster.name;
        }
        return monster.getClass().getName();
    }
}
