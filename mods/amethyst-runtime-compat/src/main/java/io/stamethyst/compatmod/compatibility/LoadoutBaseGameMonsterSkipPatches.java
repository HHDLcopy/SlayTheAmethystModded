package io.stamethyst.compatmod.compatibility;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import io.stamethyst.compatmod.core.StartupCacheRuntimeConfig;

public final class LoadoutBaseGameMonsterSkipPatches {
    private static final String MOD_ID = "loadout";

    private LoadoutBaseGameMonsterSkipPatches() {
    }

    @SpirePatch2(
        cls = "loadout.LoadoutMod",
        method = "addBaseGameMonsters",
        requiredModId = MOD_ID,
        optional = true
    )
    public static class AddBaseGameMonstersPatch {
        @SpirePrefixPatch
        public static SpireReturn<Void> Prefix() {
            if (!StartupCacheRuntimeConfig.isPatchCacheCurrent()) {
                return SpireReturn.Continue();
            }
            return SpireReturn.Return(null);
        }
    }
}
