package io.stamethyst.compatmod.compatibility;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class TogetherInSpireEasyTierAutofillPatches {
    private static final String MOD_ID = "spireTogether";
    private static final String SPIRE_TOGETHER_MOD_CLASS = "spireTogether.SpireTogetherMod";
    private static final String INPUT_FIELD_CLASS = "spireTogether.ui.elements.mixed.InputField";
    private static final String HOST_IP_PROPERTY = "amethyst.easytier.together_in_spire.host_ip";
    private static final String PORT_PROPERTY = "amethyst.easytier.together_in_spire.port";
    private static final String DEFAULT_PORT = "33455";

    private static volatile Class<?> spireTogetherModClass;
    private static volatile Method inputFieldSetTextMethod;

    private TogetherInSpireEasyTierAutofillPatches() {
    }

    @SpirePatch2(
        cls = SPIRE_TOGETHER_MOD_CLASS,
        method = "initialize",
        requiredModId = MOD_ID,
        optional = true
    )
    public static class SpireTogetherInitializePatch {
        @SpirePostfixPatch
        public static void Postfix() {
            applySharedDefaults();
        }
    }

    @SpirePatch2(
        cls = "spireTogether.screens.lobby.MPHostScreen",
        method = "init",
        requiredModId = MOD_ID,
        optional = true
    )
    public static class HostScreenInitPatch {
        @SpirePrefixPatch
        public static void Prefix() {
            applySharedDefaults();
        }
    }

    @SpirePatch2(
        cls = "spireTogether.screens.lobby.MPJoinScreen",
        method = "init",
        requiredModId = MOD_ID,
        optional = true
    )
    public static class JoinScreenInitPatch {
        @SpirePrefixPatch
        public static void Prefix() {
            applySharedDefaults();
        }

        @SpirePostfixPatch
        public static void Postfix(Object __instance) {
            applyJoinScreenDefaults(__instance);
        }
    }

    private static void applySharedDefaults() {
        String port = resolvePort();
        setSpireStaticString("HostPort", port);
        setSpireStaticString("ConnectPort", port);
        String hostIp = resolveHostIp();
        if (!hostIp.isEmpty()) {
            setSpireStaticString("IP", hostIp);
        }
    }

    private static void applyJoinScreenDefaults(Object joinScreen) {
        if (joinScreen == null) {
            return;
        }
        String hostIp = resolveHostIp();
        if (!hostIp.isEmpty()) {
            setInputFieldText(joinScreen, "IPInputfield", hostIp);
        }
        setInputFieldText(joinScreen, "PortInputfield", resolvePort());
    }

    private static String resolveHostIp() {
        return normalizeIpv4Host(System.getProperty(HOST_IP_PROPERTY, ""));
    }

    private static String resolvePort() {
        String port = String.valueOf(System.getProperty(PORT_PROPERTY, DEFAULT_PORT)).trim();
        return port.isEmpty() ? DEFAULT_PORT : port;
    }

    private static String normalizeIpv4Host(String value) {
        String normalized = String.valueOf(value).trim();
        if (normalized.isEmpty()) {
            return "";
        }
        String host = normalized.split("/", 2)[0].trim();
        String[] octets = host.split("\\.");
        if (octets.length != 4) {
            return "";
        }
        for (String octet : octets) {
            if (octet.isEmpty()) {
                return "";
            }
            for (int index = 0; index < octet.length(); index++) {
                if (!Character.isDigit(octet.charAt(index))) {
                    return "";
                }
            }
            int parsed = Integer.parseInt(octet);
            if (parsed < 0 || parsed > 255) {
                return "";
            }
        }
        return host;
    }

    private static void setInputFieldText(Object screen, String fieldName, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        try {
            Object inputField = resolveField(screen.getClass(), fieldName).get(screen);
            if (inputField != null) {
                resolveInputFieldSetTextMethod(inputField.getClass().getClassLoader()).invoke(inputField, value);
            }
        } catch (Exception ignored) {
        }
    }

    private static void setSpireStaticString(String fieldName, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        try {
            Field field = resolveSpireTogetherModClass().getField(fieldName);
            field.set(null, value);
        } catch (Exception ignored) {
        }
    }

    private static Class<?> resolveSpireTogetherModClass() throws ClassNotFoundException {
        Class<?> resolved = spireTogetherModClass;
        if (resolved == null) {
            resolved = Class.forName(
                SPIRE_TOGETHER_MOD_CLASS,
                false,
                TogetherInSpireEasyTierAutofillPatches.class.getClassLoader()
            );
            spireTogetherModClass = resolved;
        }
        return resolved;
    }

    private static Method resolveInputFieldSetTextMethod(ClassLoader loader) throws Exception {
        Method resolved = inputFieldSetTextMethod;
        if (resolved == null) {
            resolved = Class.forName(INPUT_FIELD_CLASS, false, loader)
                .getMethod("SetText", String.class);
            inputFieldSetTextMethod = resolved;
        }
        return resolved;
    }

    private static Field resolveField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
