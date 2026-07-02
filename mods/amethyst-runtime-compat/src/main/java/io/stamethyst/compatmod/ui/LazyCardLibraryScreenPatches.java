package io.stamethyst.compatmod.ui;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.screens.compendium.CardLibraryScreen;
import com.megacrit.cardcrawl.screens.mainMenu.MainMenuScreen;
import io.stamethyst.compatmod.core.StartupCacheRuntimeConfig;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public final class LazyCardLibraryScreenPatches {
    private static final String ENABLED_PROP = "amethyst.runtime_compat.lazy_card_library_screen";
    private static final ThreadLocal<Integer> MAIN_MENU_CONSTRUCTOR_DEPTH =
        new ThreadLocal<Integer>();
    private static final ThreadLocal<Boolean> FORCE_INITIALIZING = new ThreadLocal<Boolean>();
    private static final Set<CardLibraryScreen> DEFERRED_SCREENS =
        Collections.newSetFromMap(new IdentityHashMap<CardLibraryScreen, Boolean>());

    private LazyCardLibraryScreenPatches() {
    }

    @SpirePatch2(
        clz = MainMenuScreen.class,
        method = SpirePatch.CONSTRUCTOR,
        paramtypez = {boolean.class}
    )
    public static class MainMenuScreenConstructorPatch {
        @SpirePrefixPatch
        public static void Prefix(MainMenuScreen __instance) {
            if (!isEnabled()) {
                return;
            }
            MAIN_MENU_CONSTRUCTOR_DEPTH.set(mainMenuConstructorDepth() + 1);
        }

        @SpirePostfixPatch
        public static void Postfix(MainMenuScreen __instance) {
            if (!isEnabled()) {
                return;
            }
            int depth = Math.max(0, mainMenuConstructorDepth() - 1);
            if (depth == 0) {
                MAIN_MENU_CONSTRUCTOR_DEPTH.remove();
            } else {
                MAIN_MENU_CONSTRUCTOR_DEPTH.set(depth);
            }
        }
    }

    @SpirePatch2(
        clz = CardLibraryScreen.class,
        method = "initialize"
    )
    public static class CardLibraryInitializePatch {
        @SpirePrefixPatch
        public static SpireReturn<Void> Prefix(CardLibraryScreen __instance) {
            if (!shouldDeferStartupInitialize(__instance)) {
                return SpireReturn.Continue();
            }
            synchronized (DEFERRED_SCREENS) {
                DEFERRED_SCREENS.add(__instance);
            }
            System.out.println(
                "[amethyst-runtime-compat] Deferred CardLibraryScreen.initialize during cache startup"
            );
            return SpireReturn.Return(null);
        }
    }

    @SpirePatch2(
        clz = CardLibraryScreen.class,
        method = "open"
    )
    public static class CardLibraryOpenPatch {
        @SpirePrefixPatch
        public static void Prefix(CardLibraryScreen __instance) {
            ensureInitializedBeforeOpen(__instance);
        }
    }

    private static boolean shouldDeferStartupInitialize(CardLibraryScreen screen) {
        if (screen == null || !isEnabled()) {
            return false;
        }
        if (Boolean.TRUE.equals(FORCE_INITIALIZING.get())) {
            return false;
        }
        return mainMenuConstructorDepth() > 0;
    }

    private static void ensureInitializedBeforeOpen(CardLibraryScreen screen) {
        if (screen == null || !isEnabled()) {
            return;
        }
        boolean wasDeferred;
        synchronized (DEFERRED_SCREENS) {
            wasDeferred = DEFERRED_SCREENS.remove(screen);
        }
        if (!wasDeferred) {
            return;
        }
        long startedAtNs = System.nanoTime();
        FORCE_INITIALIZING.set(Boolean.TRUE);
        try {
            screen.initialize();
        } finally {
            FORCE_INITIALIZING.remove();
            long elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000L;
            System.out.println(
                "[amethyst-runtime-compat] Initialized deferred CardLibraryScreen before open took="
                    + elapsedMs
                    + "ms"
            );
        }
    }

    private static int mainMenuConstructorDepth() {
        Integer depth = MAIN_MENU_CONSTRUCTOR_DEPTH.get();
        return depth == null ? 0 : depth;
    }

    private static boolean isEnabled() {
        return StartupCacheRuntimeConfig.isCacheFeatureEnabled(ENABLED_PROP, true);
    }
}
