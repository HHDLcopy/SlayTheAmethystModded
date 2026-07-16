package io.stamethyst.compatmod.compatibility;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;

import java.util.List;

public final class TogetherInSpireRelicPatches {
    private static final String WINGED_GREAVES_ID = "WingedGreaves";

    private TogetherInSpireRelicPatches() {
    }

    @SpirePatch2(
        clz = AbstractDungeon.class,
        method = "initializeRelicList",
        requiredModId = TogetherInSpireCompatRuntime.MOD_ID,
        optional = true
    )
    public static class RemoveWingedGreavesFromRunPoolsPatch {
        @SpirePostfixPatch
        public static void Postfix() {
            removeWingedGreavesFromRunPools();
        }
    }

    @SpirePatch2(
        clz = AbstractDungeon.class,
        method = "returnRandomRelicKey",
        requiredModId = TogetherInSpireCompatRuntime.MOD_ID,
        optional = true
    )
    public static class RemoveWingedGreavesBeforeRelicSelectionPatch {
        @SpirePrefixPatch
        public static void Prefix() {
            removeWingedGreavesFromRunPools();
        }
    }

    static void removeWingedGreaves(List<String> relicPool, boolean multiplayer) {
        if (multiplayer && relicPool != null) {
            relicPool.removeIf(WINGED_GREAVES_ID::equals);
        }
    }

    private static void removeWingedGreavesFromRunPools() {
        if (!TogetherInSpireCompatRuntime.isConnected()) {
            return;
        }
        removeWingedGreaves(AbstractDungeon.commonRelicPool, true);
        removeWingedGreaves(AbstractDungeon.uncommonRelicPool, true);
        removeWingedGreaves(AbstractDungeon.rareRelicPool, true);
        removeWingedGreaves(AbstractDungeon.shopRelicPool, true);
        removeWingedGreaves(AbstractDungeon.bossRelicPool, true);
    }
}
