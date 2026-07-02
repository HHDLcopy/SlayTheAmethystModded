package io.stamethyst.compatmod.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.screens.mainMenu.MainMenuScreen;
import io.stamethyst.compatmod.core.StartupCacheRuntimeConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FastCacheSplashScreenPatches {
    private static final String ENABLED_PROP = "amethyst.runtime_compat.fast_cache_splash";
    private static final String BRIDGE_EVENTS_PROP = "amethyst.bridge.events";
    private static final String VISIBLE_HOLD_SECONDS_PROP =
        "amethyst.runtime_compat.fast_cache_splash_visible_hold_seconds";
    private static final String FADE_OUT_SECONDS_PROP =
        "amethyst.runtime_compat.fast_cache_splash_fade_out_seconds";
    private static final float DEFAULT_VISIBLE_HOLD_SECONDS = 0.72f;
    private static final float MIN_VISIBLE_HOLD_SECONDS = 0.60f;
    private static final float DEFAULT_FADE_OUT_SECONDS = 0.18f;
    private static final float MIN_FADE_OUT_SECONDS = 0.08f;
    private static final float VISIBLE_ALPHA_THRESHOLD = 0.85f;
    private static final Object EVENT_WRITE_LOCK = new Object();
    private static final AtomicBoolean splashEventWritten = new AtomicBoolean(false);
    private static final AtomicBoolean readyEventWritten = new AtomicBoolean(false);

    private static Field phaseField;
    private static Field timerField;
    private static Field colorField;
    private static Field bgColorField;

    private FastCacheSplashScreenPatches() {
    }

    @SpirePatch2(
        cls = "com.megacrit.cardcrawl.screens.splash.SplashScreen",
        method = SpirePatch.CONSTRUCTOR
    )
    public static class SplashScreenConstructorPatch {
        @SpirePostfixPatch
        public static void Postfix(Object __instance) {
            applyFastCacheSplash(__instance);
        }
    }

    @SpirePatch2(
        cls = "com.megacrit.cardcrawl.screens.splash.SplashScreen",
        method = "render",
        paramtypez = {SpriteBatch.class}
    )
    public static class SplashScreenRenderPatch {
        @SpirePostfixPatch
        public static void Postfix(Object __instance) {
            signalSplashAfterVisibleRender(__instance);
        }
    }

    @SpirePatch2(
        clz = MainMenuScreen.class,
        method = SpirePatch.CONSTRUCTOR,
        paramtypez = {boolean.class}
    )
    public static class MainMenuScreenConstructorPatch {
        @SpirePostfixPatch
        public static void Postfix(MainMenuScreen __instance) {
            signalReadyAfterMainMenuCreated();
        }
    }

    private static void applyFastCacheSplash(Object splashScreen) {
        if (splashScreen == null || !isEnabled()) {
            return;
        }
        try {
            Field phase = resolvePhaseField(splashScreen);
            Object waitPhase = enumConstant(phase.getType(), "WAIT");
            if (waitPhase == null) {
                return;
            }
            phase.set(splashScreen, waitPhase);
            resolveTimerField(splashScreen).setFloat(splashScreen, visibleHoldSeconds());
            setColor(resolveColorField(splashScreen), splashScreen, 1.0f, 1.0f, 1.0f, 1.0f);
            setColor(resolveBgColorField(splashScreen), splashScreen, 0.0f, 0.0f, 0.0f, 1.0f);
            System.out.println(
                "[amethyst-runtime-compat] Fast cache splash enabled holdSeconds="
                    + visibleHoldSeconds()
            );
        } catch (Throwable throwable) {
            System.out.println(
                "[amethyst-runtime-compat] Fast cache splash unavailable: "
                    + throwable.getClass().getName()
                    + ": "
                    + throwable.getMessage()
            );
        }
    }

    private static void signalSplashAfterVisibleRender(Object splashScreen) {
        if (splashScreen == null || !isEnabled() || splashEventWritten.get()) {
            return;
        }
        try {
            String phaseName = readPhaseName(splashScreen);
            if ("INIT".equals(phaseName) || phaseName.length() == 0) {
                return;
            }
            float alpha = readLogoAlpha(splashScreen);
            if (!Float.isNaN(alpha) && alpha < VISIBLE_ALPHA_THRESHOLD) {
                return;
            }
            if (splashEventWritten.compareAndSet(false, true)) {
                writeBootBridgeEvent("SPLASH", 94, "@amethyst.startup/game_splash");
                accelerateFadeOut(splashScreen);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void accelerateFadeOut(Object splashScreen) throws Exception {
        Field phase = resolvePhaseField(splashScreen);
        Object fadeOutPhase = enumConstant(phase.getType(), "FADE_OUT");
        if (fadeOutPhase == null) {
            return;
        }
        phase.set(splashScreen, fadeOutPhase);
        resolveTimerField(splashScreen).setFloat(splashScreen, splashFadeOutSeconds());
    }

    private static void signalReadyAfterMainMenuCreated() {
        if (!isEnabled() || readyEventWritten.get()) {
            return;
        }
        if (readyEventWritten.compareAndSet(false, true)) {
            writeBootBridgeEvent("READY", 100, "@amethyst.startup/game_ready");
        }
    }

    private static String readPhaseName(Object splashScreen) throws Exception {
        Object phase = resolvePhaseField(splashScreen).get(splashScreen);
        if (phase instanceof Enum<?>) {
            return ((Enum<?>) phase).name();
        }
        return phase == null ? "" : String.valueOf(phase);
    }

    private static float readLogoAlpha(Object splashScreen) throws Exception {
        Object color = resolveColorField(splashScreen).get(splashScreen);
        if (!(color instanceof Color)) {
            return Float.NaN;
        }
        return ((Color) color).a;
    }

    private static Field resolvePhaseField(Object splashScreen) throws NoSuchFieldException {
        if (phaseField == null) {
            phaseField = resolveField(splashScreen, "phase");
        }
        return phaseField;
    }

    private static Field resolveTimerField(Object splashScreen) throws NoSuchFieldException {
        if (timerField == null) {
            timerField = resolveField(splashScreen, "timer");
        }
        return timerField;
    }

    private static Field resolveColorField(Object splashScreen) throws NoSuchFieldException {
        if (colorField == null) {
            colorField = resolveField(splashScreen, "color");
        }
        return colorField;
    }

    private static Field resolveBgColorField(Object splashScreen) throws NoSuchFieldException {
        if (bgColorField == null) {
            bgColorField = resolveField(splashScreen, "bgColor");
        }
        return bgColorField;
    }

    private static Field resolveField(Object instance, String name) throws NoSuchFieldException {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object enumConstant(Class<?> enumClass, String name) {
        Object[] constants = enumClass.getEnumConstants();
        if (constants == null) {
            return null;
        }
        for (Object constant : constants) {
            if (name.equals(((Enum<?>) constant).name())) {
                return constant;
            }
        }
        return null;
    }

    private static void setColor(
        Field field,
        Object owner,
        float r,
        float g,
        float b,
        float a
    ) throws IllegalAccessException {
        Object value = field.get(owner);
        if (value instanceof Color) {
            ((Color) value).set(r, g, b, a);
        }
    }

    private static void writeBootBridgeEvent(String type, int progress, String message) {
        String path = System.getProperty(BRIDGE_EVENTS_PROP, "").trim();
        if (path.length() == 0) {
            return;
        }
        byte[] bytes = (type + "\t" + progress + "\t" + message + "\n")
            .getBytes(StandardCharsets.UTF_8);
        synchronized (EVENT_WRITE_LOCK) {
            try (FileOutputStream output = new FileOutputStream(new File(path), true)) {
                output.write(bytes);
                output.flush();
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isEnabled() {
        return StartupCacheRuntimeConfig.isCacheFeatureEnabled(ENABLED_PROP, true);
    }

    private static float visibleHoldSeconds() {
        String configured = System.getProperty(VISIBLE_HOLD_SECONDS_PROP);
        if (configured == null || configured.trim().length() == 0) {
            return DEFAULT_VISIBLE_HOLD_SECONDS;
        }
        try {
            return Math.max(MIN_VISIBLE_HOLD_SECONDS, Float.parseFloat(configured.trim()));
        } catch (NumberFormatException ignored) {
            return DEFAULT_VISIBLE_HOLD_SECONDS;
        }
    }

    private static float splashFadeOutSeconds() {
        String configured = System.getProperty(FADE_OUT_SECONDS_PROP);
        if (configured == null || configured.trim().length() == 0) {
            return DEFAULT_FADE_OUT_SECONDS;
        }
        try {
            return Math.max(MIN_FADE_OUT_SECONDS, Float.parseFloat(configured.trim()));
        } catch (NumberFormatException ignored) {
            return DEFAULT_FADE_OUT_SECONDS;
        }
    }

}
