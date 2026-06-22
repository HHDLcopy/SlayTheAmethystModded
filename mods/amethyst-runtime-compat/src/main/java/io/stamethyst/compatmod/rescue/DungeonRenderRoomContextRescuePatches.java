package io.stamethyst.compatmod.rescue;

import io.stamethyst.compatmod.core.CompatRuntimeState;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;

public final class DungeonRenderRoomContextRescuePatches {
    private DungeonRenderRoomContextRescuePatches() {
    }

    @SpirePatch2(
        clz = AbstractDungeon.class,
        method = "render",
        paramtypez = {SpriteBatch.class}
    )
    public static class AbstractDungeonRenderPatch {
        @SpirePrefixPatch
        public static SpireReturn<Void> Prefix() {
            if (!shouldSkipRender("dungeon_render_room_context", "Skipped AbstractDungeon.render")) {
                return SpireReturn.Continue();
            }
            return SpireReturn.Return();
        }
    }

    @SpirePatch2(
        clz = AbstractPlayer.class,
        method = "render",
        paramtypez = {SpriteBatch.class}
    )
    public static class AbstractPlayerRenderPatch {
        @SpirePrefixPatch
        public static SpireReturn<Void> Prefix() {
            if (!shouldSkipRender("player_render_room_context", "Skipped AbstractPlayer.render")) {
                return SpireReturn.Continue();
            }
            return SpireReturn.Return();
        }
    }

    private static boolean shouldSkipRender(String key, String detail) {
        if (!CompatRuntimeState.isDungeonRenderRoomContextRescueEnabled()
            || RoomContextRescueRuntime.hasCurrentRoom()) {
            return false;
        }
        RoomStateRescueNoticeBridge.notifyRescue(
            key,
            detail + " because the current room was unavailable"
        );
        return true;
    }
}
