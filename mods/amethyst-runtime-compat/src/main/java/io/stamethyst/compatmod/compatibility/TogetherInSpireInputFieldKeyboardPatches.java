package io.stamethyst.compatmod.compatibility;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

public final class TogetherInSpireInputFieldKeyboardPatches {
    private static final String INPUT_FIELD_BUTTON_CLASS =
        "spireTogether.ui.elements.mixed.InputField$2";
    private static final String KEYBOARD_REQUEST_PROPERTY =
        "amethyst.in_game_keyboard_request";
    private static final String REQUEST_SOURCE =
        "system_keyboard_preview:together_in_spire";

    private static volatile Field ownerField;
    private static volatile Method getTextMethod;
    private static volatile Field charFilterField;
    private static volatile Field characterMaxField;

    private TogetherInSpireInputFieldKeyboardPatches() {
    }

    @SpirePatch2(
        cls = INPUT_FIELD_BUTTON_CLASS,
        method = "OnLeftClick",
        requiredModId = TogetherInSpireCompatRuntime.MOD_ID,
        optional = true
    )
    public static class InputFieldClickPatch {
        @SpirePostfixPatch
        public static void Postfix(Object __instance) {
            requestAndroidKeyboard(__instance);
        }
    }

    private static void requestAndroidKeyboard(Object inputFieldButton) {
        String requestPath = System.getProperty(KEYBOARD_REQUEST_PROPERTY, "").trim();
        if (requestPath.isEmpty() || inputFieldButton == null) {
            return;
        }
        try {
            Object inputField = readInputFieldOwner(inputFieldButton);
            if (inputField == null) {
                return;
            }
            writeRequest(
                new File(requestPath),
                readInputFieldText(inputField),
                readAllowedCharacters(inputField),
                readCharacterLimit(inputField)
            );
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
        }
    }

    private static Object readInputFieldOwner(Object button) throws ReflectiveOperationException {
        Field field = ownerField;
        if (field == null || field.getDeclaringClass() != button.getClass()) {
            field = button.getClass().getDeclaredField("this$0");
            field.setAccessible(true);
            ownerField = field;
        }
        return field.get(button);
    }

    private static String readInputFieldText(Object inputField)
        throws ReflectiveOperationException {
        Method method = getTextMethod;
        if (method == null || method.getDeclaringClass() != inputField.getClass()) {
            method = inputField.getClass().getMethod("GetText");
            getTextMethod = method;
        }
        Object value = method.invoke(inputField);
        return value == null ? "" : String.valueOf(value);
    }

    private static String readAllowedCharacters(Object inputField)
        throws ReflectiveOperationException {
        Field field = charFilterField;
        if (field == null || field.getDeclaringClass() != inputField.getClass()) {
            field = inputField.getClass().getField("charFilter");
            charFilterField = field;
        }
        Object value = field.get(inputField);
        if (!(value instanceof List<?>)) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (Object item : (List<?>) value) {
            if (item instanceof Character) {
                result.append(((Character) item).charValue());
            }
        }
        return result.toString();
    }

    private static int readCharacterLimit(Object inputField)
        throws ReflectiveOperationException {
        Field field = characterMaxField;
        if (field == null || field.getDeclaringClass() != inputField.getClass()) {
            field = inputField.getClass().getField("characterMax");
            characterMaxField = field;
        }
        Object value = field.get(inputField);
        return value instanceof Number ? ((Number) value).intValue() : -1;
    }

    static String encodePayloadValue(String value) {
        String safeValue = value == null ? "" : value;
        return Base64.getEncoder().encodeToString(
            safeValue.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void writeRequest(
        File requestFile,
        String initialText,
        String allowedCharacters,
        int characterLimit
    ) {
        File parent = requestFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return;
        }
        try (FileWriter writer = new FileWriter(requestFile, false)) {
            writer.write(REQUEST_SOURCE);
            writer.write('\n');
            writer.write(Long.toString(System.currentTimeMillis()));
            writer.write('\n');
            writer.write(encodePayloadValue(initialText));
            writer.write('\n');
            writer.write(encodePayloadValue(allowedCharacters));
            writer.write('\n');
            writer.write(Integer.toString(characterLimit));
            writer.write('\n');
        } catch (IOException ignored) {
        }
    }
}
