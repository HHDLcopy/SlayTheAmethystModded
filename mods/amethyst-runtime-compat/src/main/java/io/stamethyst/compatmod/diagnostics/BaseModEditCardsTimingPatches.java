package io.stamethyst.compatmod.diagnostics;

import basemod.interfaces.EditCardsSubscriber;

import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;

import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

public final class BaseModEditCardsTimingPatches {
    private static final String ENABLED_PROP = "amethyst.runtime_compat.profile_edit_cards";
    private static final String THRESHOLD_MS_PROP = "amethyst.runtime_compat.profile_edit_cards_threshold_ms";

    private BaseModEditCardsTimingPatches() {
    }

    @SpirePatch2(
        cls = "basemod.BaseMod",
        method = "publishEditCards",
        requiredModId = "basemod",
        optional = true
    )
    public static class PublishEditCardsPatch {
        @SpireInstrumentPatch
        public static ExprEditor Instrument() {
            return new ExprEditor() {
                @Override
                public void edit(MethodCall call) throws CannotCompileException {
                    if (!EditCardsSubscriber.class.getName().equals(call.getClassName())) {
                        return;
                    }
                    if (!"receiveEditCards".equals(call.getMethodName())) {
                        return;
                    }
                    call.replace(
                        "{ "
                            + BaseModEditCardsTimingPatches.class.getName()
                            + ".invokeReceiveEditCards($0); "
                            + "}"
                    );
                }
            };
        }
    }

    public static void invokeReceiveEditCards(EditCardsSubscriber subscriber) {
        if (!isEnabled()) {
            subscriber.receiveEditCards();
            return;
        }
        long startedAtNs = System.nanoTime();
        try {
            subscriber.receiveEditCards();
        } finally {
            long elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000L;
            if (elapsedMs >= thresholdMs()) {
                System.out.println(
                    "[amethyst-runtime-compat] BaseMod.receiveEditCards "
                        + describeSubscriber(subscriber)
                        + " took="
                        + elapsedMs
                        + "ms"
                );
            }
        }
    }

    private static String describeSubscriber(EditCardsSubscriber subscriber) {
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
