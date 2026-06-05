package io.stamethyst.compatmod;

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
                                "{ $_ = "
                                    + BaseModCustomMonsterRenderRescuePatches.class.getName()
                                    + ".safeTextureWidth($0, this); }"
                            );
                            return;
                        }
                        if ("getHeight".equals(call.getMethodName())) {
                            call.replace(
                                "{ $_ = "
                                    + BaseModCustomMonsterRenderRescuePatches.class.getName()
                                    + ".safeTextureHeight($0, this); }"
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
                            + BaseModCustomMonsterRenderRescuePatches.class.getName()
                            + ".safeDrawTexture($0, $1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, this); "
                            + "}"
                    );
                }
            };
        }
    }

    public static int safeTextureWidth(Texture texture, AbstractMonster monster) {
        if (texture != null) {
            return texture.getWidth();
        }
        if (!CompatRuntimeState.isBaseModCustomMonsterRenderRescueEnabled()) {
            return texture.getWidth();
        }
        notifyMissingTexture(monster);
        return 0;
    }

    public static int safeTextureHeight(Texture texture, AbstractMonster monster) {
        if (texture != null) {
            return texture.getHeight();
        }
        if (!CompatRuntimeState.isBaseModCustomMonsterRenderRescueEnabled()) {
            return texture.getHeight();
        }
        notifyMissingTexture(monster);
        return 0;
    }

    public static void safeDrawTexture(
        SpriteBatch spriteBatch,
        Texture texture,
        float x,
        float y,
        float width,
        float height,
        int srcX,
        int srcY,
        int srcWidth,
        int srcHeight,
        boolean flipX,
        boolean flipY,
        AbstractMonster monster
    ) {
        if (texture == null && CompatRuntimeState.isBaseModCustomMonsterRenderRescueEnabled()) {
            notifyMissingTexture(monster);
            return;
        }
        spriteBatch.draw(texture, x, y, width, height, srcX, srcY, srcWidth, srcHeight, flipX, flipY);
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

