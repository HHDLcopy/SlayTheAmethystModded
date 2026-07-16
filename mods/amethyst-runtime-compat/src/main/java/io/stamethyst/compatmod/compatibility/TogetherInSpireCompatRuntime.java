package io.stamethyst.compatmod.compatibility;

import java.lang.reflect.Field;

final class TogetherInSpireCompatRuntime {
    static final String MOD_ID = "spireTogether";
    static final String SPIRE_TOGETHER_MOD_CLASS = "spireTogether.SpireTogetherMod";

    private static final String TOUCHSCREEN_ENABLED_PROP = "amethyst.touchscreen_enabled";
    private static final String NATIVE_TOUCHSCREEN_ENABLED_PROP =
        "amethyst.native_touchscreen_enabled";
    private static final String TOUCHSCREEN_POLICY_PROP = "amethyst.touchscreen_policy";
    private static final String VANILLA_ALLOWLIST_POLICY = "vanilla_allowlist";
    private static final String ROUTE_LOCK_ENABLED_PROP =
        "amethyst.runtime_compat.together_in_spire_route_lock";
    private static final String EASYTIER_AUTOFILL_ENABLED_PROP =
        "amethyst.runtime_compat.together_in_spire_easytier_autofill";

    private static volatile Field connectedField;

    private TogetherInSpireCompatRuntime() {
    }

    static boolean isHybridInputMode() {
        return !readBooleanProperty(TOUCHSCREEN_ENABLED_PROP, false)
            && readBooleanProperty(NATIVE_TOUCHSCREEN_ENABLED_PROP, false)
            && VANILLA_ALLOWLIST_POLICY.equalsIgnoreCase(
                System.getProperty(TOUCHSCREEN_POLICY_PROP, "")
            );
    }

    static boolean isConnected() {
        try {
            Field field = connectedField;
            if (field == null) {
                Class<?> type = Class.forName(
                    SPIRE_TOGETHER_MOD_CLASS,
                    false,
                    TogetherInSpireCompatRuntime.class.getClassLoader()
                );
                field = type.getField("isConnected");
                connectedField = field;
            }
            return field.getBoolean(null);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }

    static boolean isRouteLockEnabledAndConnected() {
        return isRouteLockEnabled() && isConnected();
    }

    static boolean isRouteLockEnabled() {
        return readBooleanProperty(ROUTE_LOCK_ENABLED_PROP, true);
    }

    static boolean isEasyTierAutofillEnabled() {
        return readBooleanProperty(EASYTIER_AUTOFILL_ENABLED_PROP, true);
    }

    private static boolean readBooleanProperty(String name, boolean fallback) {
        String value = System.getProperty(name);
        if (value == null) {
            return fallback;
        }
        String normalized = value.trim();
        if ("true".equalsIgnoreCase(normalized) || "1".equals(normalized)) {
            return true;
        }
        if ("false".equalsIgnoreCase(normalized) || "0".equals(normalized)) {
            return false;
        }
        return fallback;
    }
}
