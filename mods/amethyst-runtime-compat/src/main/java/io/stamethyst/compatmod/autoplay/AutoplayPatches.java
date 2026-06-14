package io.stamethyst.compatmod.autoplay;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.megacrit.cardcrawl.core.CardCrawlGame;

/**
 * Hooks {@link CardCrawlGame#update()} so the autoplay driver gets a tick every frame.
 *
 * <p>We use {@code Postfix} so we run after the engine has updated game state, which avoids
 * racing with the same-frame state mutations that update() performs.</p>
 *
 * <p>This class is intentionally split from {@link AutoplayDriver} so that the SpirePatch
 * code generation only touches a tiny class — any class that {@code @SpirePatch} annotates is
 * scanned by ModTheSpire on every launch.</p>
 */
public final class AutoplayPatches {
    private AutoplayPatches() {
    }

    @SpirePatch2(
        clz = CardCrawlGame.class,
        method = "update"
    )
    public static class CardCrawlGameUpdatePatch {
        public static void Postfix() {
            AutoplayDriver.onCardCrawlGameUpdate();
        }
    }
}
