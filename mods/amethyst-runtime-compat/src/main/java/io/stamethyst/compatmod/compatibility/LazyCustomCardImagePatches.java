package io.stamethyst.compatmod.compatibility;

import basemod.abstracts.CustomCard;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.cards.AbstractCard;
import io.stamethyst.compatmod.core.StartupCacheRuntimeConfig;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

public final class LazyCustomCardImagePatches {
    private static final String ENABLED_PROP = "amethyst.runtime_compat.lazy_custom_card_images";
    private static final String PROFILE_PROP = "amethyst.runtime_compat.lazy_custom_card_images_profile";
    private static final Map<AbstractCard, String> DEFERRED_IMAGES =
        Collections.synchronizedMap(new IdentityHashMap<AbstractCard, String>());
    private static final ThreadLocal<Boolean> FORCE_LOADING = new ThreadLocal<Boolean>();
    private static int editCardsDepth;
    private static int deferredCount;
    private static int loadedCount;

    private LazyCustomCardImagePatches() {
    }

    @SpirePatch2(
        cls = "basemod.BaseMod",
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
            synchronized (LazyCustomCardImagePatches.class) {
                editCardsDepth++;
            }
        }

        @SpirePostfixPatch
        public static void Postfix() {
            if (!isEnabled()) {
                return;
            }
            int deferred;
            synchronized (LazyCustomCardImagePatches.class) {
                if (editCardsDepth > 0) {
                    editCardsDepth--;
                }
                deferred = deferredCount;
            }
            if (deferred > 0) {
                System.out.println(
                    "[amethyst-runtime-compat] Lazy custom card images deferred="
                        + deferred
                        + " pending="
                        + DEFERRED_IMAGES.size()
                );
            }
        }
    }

    @SpirePatch2(
        cls = "basemod.abstracts.CustomCard",
        method = "loadCardImage",
        requiredModId = "basemod",
        optional = true,
        paramtypez = {String.class}
    )
    public static class CustomCardLoadCardImagePatch {
        @SpirePrefixPatch
        public static SpireReturn<Void> Prefix(CustomCard __instance, String img) {
            if (!shouldDeferImageLoad(__instance, img)) {
                return SpireReturn.Continue();
            }
            DEFERRED_IMAGES.put(__instance, img);
            synchronized (LazyCustomCardImagePatches.class) {
                deferredCount++;
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
            ensureImageLoaded(__instance);
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
            ensureImageLoaded(__instance);
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
            ensureImageLoaded(__instance);
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
            ensureImageLoaded(__instance);
        }
    }

    private static boolean shouldDeferImageLoad(CustomCard card, String img) {
        if (!isEnabled() || card == null || img == null || img.length() == 0) {
            return false;
        }
        if (Boolean.TRUE.equals(FORCE_LOADING.get())) {
            return false;
        }
        synchronized (LazyCustomCardImagePatches.class) {
            return editCardsDepth > 0;
        }
    }

    private static void ensureImageLoaded(AbstractCard card) {
        if (!(card instanceof CustomCard)) {
            return;
        }
        String img = DEFERRED_IMAGES.remove(card);
        if (img == null || img.length() == 0) {
            return;
        }
        long startedAtNs = System.nanoTime();
        FORCE_LOADING.set(Boolean.TRUE);
        try {
            ((CustomCard) card).loadCardImage(img);
            int loaded;
            synchronized (LazyCustomCardImagePatches.class) {
                loadedCount++;
                loaded = loadedCount;
            }
            if (isProfilingEnabled()) {
                System.out.println(
                    "[amethyst-runtime-compat] Lazy custom card image loaded card="
                        + safeCardId(card)
                        + " elapsedMs="
                        + elapsedMs(startedAtNs)
                        + " loaded="
                        + loaded
                        + " pending="
                        + DEFERRED_IMAGES.size()
                );
            }
        } catch (Throwable throwable) {
            System.out.println(
                "[amethyst-runtime-compat] Lazy custom card image failed card="
                    + safeCardId(card)
                    + " img="
                    + img
                    + " error="
                    + throwable.getClass().getName()
                    + ": "
                    + throwable.getMessage()
            );
        } finally {
            FORCE_LOADING.remove();
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
