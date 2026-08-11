package io.stamethyst.compatmod.achievement;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.megacrit.cardcrawl.unlock.UnlockTracker;

/** Observes vanilla achievement unlock completion without replacing its persistence logic. */
public final class AchievementPatches {
    private AchievementPatches() {
    }

    @SpirePatch2(clz = UnlockTracker.class, method = "unlockAchievement")
    public static class UnlockAchievementPatch {
        public static void Postfix(Object[] __args) {
            if (__args != null && __args.length > 0 && __args[0] instanceof String) {
                String id = (String) __args[0];
                if (UnlockTracker.isAchievementUnlocked(id)) {
                    AchievementBridge.reportUnlocked(id);
                }
            }
        }
    }

    @SpirePatch2(clz = UnlockTracker.class, method = "unlockLuckyDay")
    public static class UnlockLuckyDayPatch {
        public static void Postfix() {
            if (UnlockTracker.isAchievementUnlocked("lucky_day")) {
                AchievementBridge.reportUnlocked("lucky_day");
            }
        }
    }
}
