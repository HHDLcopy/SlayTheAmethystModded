package io.stamethyst.compatmod.presence;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;

/**
 * Intercepts floor-transition events in the vanilla dungeon to keep the
 * rich-presence IPC file up to date via {@link RichPresenceBridge}.
 *
 * <p>Patch domain: {@code rich_presence}
 * <p>Fix: reports current character, floor, and act to the launcher whenever
 * the player moves to a new floor, so Steam Rich Presence reflects live
 * game state.
 * <p>Patch class: {@link RichPresencePatches.NextRoomTransitionStartPatch}
 */
public final class RichPresencePatches {
    private RichPresencePatches() {
    }

    /**
     * Fires after the static {@code AbstractDungeon.nextRoomTransitionStart()} which
     * increments {@code floorNum} and wires up the next room before the dungeon enters
     * it — covers every floor advance including the initial entry into floor 1.
     */
    @SpirePatch2(clz = AbstractDungeon.class, method = "nextRoomTransitionStart")
    public static class NextRoomTransitionStartPatch {
        public static void Postfix() {
            RichPresenceBridge.updateDungeonState();
        }
    }
}
