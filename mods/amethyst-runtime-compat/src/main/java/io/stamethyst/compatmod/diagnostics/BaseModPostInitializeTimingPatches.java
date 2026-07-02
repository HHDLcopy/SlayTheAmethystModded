package io.stamethyst.compatmod.diagnostics;

import basemod.interfaces.PostInitializeSubscriber;

import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;

import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

public final class BaseModPostInitializeTimingPatches {
    private static final String ENABLED_PROP = "amethyst.runtime_compat.profile_post_initialize";
    private static final String THRESHOLD_MS_PROP = "amethyst.runtime_compat.profile_post_initialize_threshold_ms";

    private BaseModPostInitializeTimingPatches() {
    }

    @SpirePatch2(
        cls = "basemod.BaseMod",
        method = "publishPostInitialize",
        requiredModId = "basemod",
        optional = true
    )
    public static class PublishPostInitializePatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return new ExprEditor() {
                @Override
                public void edit(MethodCall call) throws CannotCompileException {
                    if (!PostInitializeSubscriber.class.getName().equals(call.getClassName())) {
                        return;
                    }
                    if (!"receivePostInitialize".equals(call.getMethodName())) {
                        return;
                    }
                    call.replace(
                        "{ "
                            + BaseModPostInitializeTimingPatches.class.getName()
                            + ".invokeReceivePostInitialize($0); "
                            + "}"
                    );
                }
            };
        }
    }

    public static void invokeReceivePostInitialize(PostInitializeSubscriber subscriber) {
        if (!isEnabled()) {
            subscriber.receivePostInitialize();
            return;
        }
        long startedAtNs = System.nanoTime();
        try {
            subscriber.receivePostInitialize();
        } finally {
            long elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000L;
            if (elapsedMs >= thresholdMs()) {
                System.out.println(
                    "[amethyst-runtime-compat] BaseMod.receivePostInitialize "
                        + describeSubscriber(subscriber)
                        + " took="
                        + elapsedMs
                        + "ms"
                );
            }
        }
    }

    private static String describeSubscriber(PostInitializeSubscriber subscriber) {
        if (subscriber == null) {
            return "<null>";
        }
        return subscriber.getClass().getName();
    }

    private static boolean isEnabled() {
        String value = System.getProperty(ENABLED_PROP);
        if (value == null || value.trim().length() == 0) {
            return true;
        }
        return "true".equalsIgnoreCase(value)
            || "1".equals(value)
            || "yes".equalsIgnoreCase(value)
            || "on".equalsIgnoreCase(value);
    }

    private static long thresholdMs() {
        String value = System.getProperty(THRESHOLD_MS_PROP);
        if (value == null || value.trim().length() == 0) {
            return 25L;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return Math.max(0L, parsed);
        } catch (NumberFormatException ignored) {
            return 25L;
        }
    }
}
