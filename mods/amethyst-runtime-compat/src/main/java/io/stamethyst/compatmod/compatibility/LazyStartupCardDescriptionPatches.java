package io.stamethyst.compatmod.compatibility;

import basemod.BaseMod;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import io.stamethyst.compatmod.core.StartupCacheRuntimeConfig;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public final class LazyStartupCardDescriptionPatches {
    private static final String ENABLED_PROP = "amethyst.runtime_compat.lazy_startup_card_descriptions";
    private static final String PROFILE_PROP = "amethyst.runtime_compat.lazy_startup_card_descriptions_profile";
    private static final Set<AbstractCard> DEFERRED_CARDS =
        Collections.newSetFromMap(new IdentityHashMap<AbstractCard, Boolean>());
    private static final ThreadLocal<Boolean> FORCE_INITIALIZING = new ThreadLocal<Boolean>();
    private static int editCardsDepth;
    private static int deferredCalls;
    private static int initializedCount;

    private LazyStartupCardDescriptionPatches() {
    }

    @SpirePatch2(
        clz = BaseMod.class,
        method = "publishEditCards",
        requiredModId = "basemod",
        optional = true
    )
    public static class BaseModPublishEditCardsPatch {
        @SpirePrefixPatch
        public static void Prefix() {
            if (!isEnabled()) {
                return;
            }
            synchronized (LazyStartupCardDescriptionPatches.class) {
                editCardsDepth++;
            }
        }

        @SpirePostfixPatch
        public static void Postfix() {
            if (!isEnabled()) {
                return;
            }
            int deferred;
            int pending;
            synchronized (LazyStartupCardDescriptionPatches.class) {
                if (editCardsDepth > 0) {
                    editCardsDepth--;
                }
                deferred = deferredCalls;
                synchronized (DEFERRED_CARDS) {
                    pending = DEFERRED_CARDS.size();
                }
            }
            if (deferred > 0) {
                System.out.println(
                    "[amethyst-runtime-compat] Lazy startup card descriptions deferredCalls="
                        + deferred
                        + " pendingCards="
                        + pending
                );
            }
        }
    }

    @SpirePatch2(
        clz = AbstractCard.class,
        method = "initializeDescription"
    )
    public static class AbstractCardInitializeDescriptionPatch {
        @SpirePrefixPatch
        public static SpireReturn<Void> Prefix(AbstractCard __instance) {
            if (!shouldDeferDescription(__instance)) {
                return SpireReturn.Continue();
            }
            synchronized (DEFERRED_CARDS) {
                DEFERRED_CARDS.add(__instance);
            }
            synchronized (LazyStartupCardDescriptionPatches.class) {
                deferredCalls++;
            }
            return SpireReturn.Return(null);
        }
    }

    @SpirePatch2(
        clz = AbstractCard.class,
        method = "render",
        paramtypez = {SpriteBatch.class}
    )
    public static class AbstractCardRenderPatch {
        @SpirePrefixPatch
        public static void Prefix(AbstractCard __instance) {
            ensureDescriptionInitialized(__instance);
        }
    }

    @SpirePatch2(
        clz = AbstractCard.class,
        method = "render",
        paramtypez = {SpriteBatch.class, boolean.class}
    )
    public static class AbstractCardRenderWithHoverPatch {
        @SpirePrefixPatch
        public static void Prefix(AbstractCard __instance) {
            ensureDescriptionInitialized(__instance);
        }
    }

    @SpirePatch2(
        clz = AbstractCard.class,
        method = "renderInLibrary",
        paramtypez = {SpriteBatch.class}
    )
    public static class AbstractCardRenderInLibraryPatch {
        @SpirePrefixPatch
        public static void Prefix(AbstractCard __instance) {
            ensureDescriptionInitialized(__instance);
        }
    }

    @SpirePatch2(
        clz = AbstractCard.class,
        method = "renderUpgradePreview",
        paramtypez = {SpriteBatch.class}
    )
    public static class AbstractCardRenderUpgradePreviewPatch {
        @SpirePrefixPatch
        public static void Prefix(AbstractCard __instance) {
            ensureDescriptionInitialized(__instance);
        }
    }

    @SpirePatch2(
        clz = AbstractCard.class,
        method = "renderWithSelections",
        paramtypez = {SpriteBatch.class}
    )
    public static class AbstractCardRenderWithSelectionsPatch {
        @SpirePrefixPatch
        public static void Prefix(AbstractCard __instance) {
            ensureDescriptionInitialized(__instance);
        }
    }

    @SpirePatch2(
        clz = AbstractCard.class,
        method = "renderCardTip",
        paramtypez = {SpriteBatch.class}
    )
    public static class AbstractCardRenderCardTipPatch {
        @SpirePrefixPatch
        public static void Prefix(AbstractCard __instance) {
            ensureDescriptionInitialized(__instance);
        }
    }

    @SpirePatch2(
        clz = AbstractCard.class,
        method = "renderCardPreview",
        paramtypez = {SpriteBatch.class}
    )
    public static class AbstractCardRenderCardPreviewPatch {
        @SpirePrefixPatch
        public static void Prefix(AbstractCard __instance) {
            ensureDescriptionInitialized(__instance);
        }
    }

    @SpirePatch2(
        clz = AbstractCard.class,
        method = "renderCardPreviewInSingleView",
        paramtypez = {SpriteBatch.class}
    )
    public static class AbstractCardRenderCardPreviewInSingleViewPatch {
        @SpirePrefixPatch
        public static void Prefix(AbstractCard __instance) {
            ensureDescriptionInitialized(__instance);
        }
    }

    @SpirePatch2(
        clz = AbstractCard.class,
        method = "displayUpgrades"
    )
    public static class AbstractCardDisplayUpgradesPatch {
        @SpirePrefixPatch
        public static void Prefix(AbstractCard __instance) {
            ensureDescriptionInitialized(__instance);
        }
    }

    @SpirePatch2(
        clz = AbstractCard.class,
        method = "makeStatEquivalentCopy"
    )
    public static class AbstractCardMakeStatEquivalentCopyPatch {
        @SpirePrefixPatch
        public static void Prefix(AbstractCard __instance) {
            ensureDescriptionInitialized(__instance);
        }
    }

    @SpirePatch2(
        clz = AbstractCard.class,
        method = "canUse",
        paramtypez = {AbstractPlayer.class, AbstractMonster.class}
    )
    public static class AbstractCardCanUsePatch {
        @SpirePrefixPatch
        public static void Prefix(AbstractCard __instance) {
            ensureDescriptionInitialized(__instance);
        }
    }

    public static void ensureDescriptionInitialized(AbstractCard card) {
        if (card == null || !isEnabled()) {
            return;
        }
        boolean wasDeferred;
        synchronized (DEFERRED_CARDS) {
            wasDeferred = DEFERRED_CARDS.remove(card);
        }
        if (!wasDeferred) {
            return;
        }
        long startedAtNs = System.nanoTime();
        FORCE_INITIALIZING.set(Boolean.TRUE);
        try {
            card.initializeDescription();
            int initialized;
            synchronized (LazyStartupCardDescriptionPatches.class) {
                initializedCount++;
                initialized = initializedCount;
            }
            if (isProfilingEnabled()) {
                System.out.println(
                    "[amethyst-runtime-compat] Lazy startup card description initialized card="
                        + safeCardId(card)
                        + " elapsedMs="
                        + elapsedMs(startedAtNs)
                        + " initialized="
                        + initialized
                        + " pending="
                        + pendingCount()
                );
            }
        } catch (Throwable throwable) {
            System.out.println(
                "[amethyst-runtime-compat] Lazy startup card description failed card="
                    + safeCardId(card)
                    + " error="
                    + throwable.getClass().getName()
                    + ": "
                    + throwable.getMessage()
            );
        } finally {
            FORCE_INITIALIZING.remove();
        }
    }

    private static boolean shouldDeferDescription(AbstractCard card) {
        if (card == null || !isEnabled()) {
            return false;
        }
        if (Boolean.TRUE.equals(FORCE_INITIALIZING.get())) {
            return false;
        }
        synchronized (LazyStartupCardDescriptionPatches.class) {
            return editCardsDepth > 0;
        }
    }

    private static int pendingCount() {
        synchronized (DEFERRED_CARDS) {
            return DEFERRED_CARDS.size();
        }
    }

    private static String safeCardId(AbstractCard card) {
        try {
            if (card.cardID != null) {
                return card.cardID;
            }
        } catch (Throwable ignored) {
        }
        return card.getClass().getName();
    }

    private static long elapsedMs(long startedAtNs) {
        return (System.nanoTime() - startedAtNs) / 1_000_000L;
    }

    private static boolean isEnabled() {
        return StartupCacheRuntimeConfig.isCacheFeatureEnabled(ENABLED_PROP, true);
    }

    private static boolean isProfilingEnabled() {
        return StartupCacheRuntimeConfig.readBooleanSystemProperty(PROFILE_PROP, false);
    }
}
