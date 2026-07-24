package io.stamethyst.compatmod.compatibility;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.Hitbox;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import com.megacrit.cardcrawl.helpers.input.InputHelper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class TogetherInSpireChatKeyboardButtonPatches {
    private static final String CHAT_CONSOLE_CLASS = "spireTogether.chat.ChatConsole";
    private static final String KEYBOARD_ICON =
        "amethyst-runtime-compat/images/together-in-spire/keyboard.png";

    private static final float CHAT_INPUT_X = 200.0f;
    private static final float CHAT_INPUT_Y = 200.0f;
    private static final float CHAT_INPUT_HEIGHT = 40.0f;
    private static final float BUTTON_SIZE = 56.0f;
    private static final float BUTTON_GAP = 8.0f;
    private static final float BUTTON_X = CHAT_INPUT_X - BUTTON_GAP - BUTTON_SIZE;
    private static final float BUTTON_Y =
        CHAT_INPUT_Y + (CHAT_INPUT_HEIGHT - BUTTON_SIZE) / 2.0f;

    private static final Color BUTTON_BACKGROUND =
        new Color(0.10f, 0.12f, 0.15f, 0.92f);
    private static final Color BUTTON_BORDER =
        new Color(0.78f, 0.73f, 0.62f, 0.95f);
    private static final char TEXT_SYNC_START = '\uE000';
    private static final char TEXT_SYNC_END = '\uE001';
    private static final int MAX_TEXT_SYNC_PAYLOAD_LENGTH = 16384;

    private static final Hitbox keyboardHitbox = new Hitbox(1.0f, 1.0f);

    private static volatile Field visibleField;
    private static volatile Field openedChatField;
    private static volatile Field currentTextField;
    private static volatile Method openMethod;
    private static volatile Method setTextMethod;
    private static final StringBuilder textSyncPayload = new StringBuilder();
    private static boolean readingTextSyncPayload;
    private static boolean discardTextSyncPayload;
    private static Texture keyboardTexture;

    private TogetherInSpireChatKeyboardButtonPatches() {
    }

    @SpirePatch2(
        cls = CHAT_CONSOLE_CLASS,
        method = "receivePostUpdate",
        requiredModId = TogetherInSpireCompatRuntime.MOD_ID,
        optional = true
    )
    public static class ChatUpdatePatch {
        @SpirePrefixPatch
        public static void Prefix() {
            if (!isButtonVisible()) {
                keyboardHitbox.hovered = false;
                return;
            }
            layoutHitbox();
            keyboardHitbox.hovered = containsPointer(keyboardHitbox);
            if (!InputHelper.justClickedLeft || !keyboardHitbox.hovered) {
                return;
            }
            InputHelper.justClickedLeft = false;
            if (!readVisible() && !openChatConsole()) {
                return;
            }
            TogetherInSpireInputFieldKeyboardPatches.requestAndroidKeyboard(
                readCurrentText(),
                "",
                -1,
                TogetherInSpireInputFieldKeyboardPatches.CHAT_REQUEST_SOURCE
            );
        }
    }

    @SpirePatch2(
        cls = CHAT_CONSOLE_CLASS,
        method = "acceptCharacter",
        paramtypez = {char.class},
        requiredModId = TogetherInSpireCompatRuntime.MOD_ID,
        optional = true
    )
    public static class ChatTextSyncPatch {
        @SpirePrefixPatch
        public static SpireReturn<Boolean> Prefix(Object __instance, Object[] __args) {
            if (__args == null || __args.length != 1 || !(__args[0] instanceof Character)) {
                return SpireReturn.Continue();
            }
            char character = ((Character) __args[0]).charValue();
            if (!readingTextSyncPayload) {
                if (character != TEXT_SYNC_START) {
                    return SpireReturn.Continue();
                }
                textSyncPayload.setLength(0);
                readingTextSyncPayload = true;
                discardTextSyncPayload = false;
                return SpireReturn.Return(false);
            }
            if (character == TEXT_SYNC_END) {
                if (!discardTextSyncPayload) {
                    applyTextSync(__instance, textSyncPayload.toString());
                }
                textSyncPayload.setLength(0);
                readingTextSyncPayload = false;
                discardTextSyncPayload = false;
                return SpireReturn.Return(false);
            }
            if (isBase64Character(character) &&
                textSyncPayload.length() < MAX_TEXT_SYNC_PAYLOAD_LENGTH
            ) {
                textSyncPayload.append(character);
            } else {
                textSyncPayload.setLength(0);
                discardTextSyncPayload = true;
            }
            return SpireReturn.Return(false);
        }
    }

    @SpirePatch2(
        cls = CHAT_CONSOLE_CLASS,
        method = "receiveRender",
        paramtypez = {SpriteBatch.class},
        requiredModId = TogetherInSpireCompatRuntime.MOD_ID,
        optional = true
    )
    public static class ChatRenderPatch {
        @SpirePostfixPatch
        public static void Postfix(SpriteBatch sb) {
            if (!isButtonVisible()) {
                return;
            }
            layoutHitbox();
            loadTexture();
            renderButton(sb);
        }
    }

    private static boolean isButtonVisible() {
        return shouldShowButton(
            TogetherInSpireInputFieldKeyboardPatches.isAndroidKeyboardAvailable(),
            readVisible(),
            TogetherInSpireCompatRuntime.isConnected(),
            readOpenedChat()
        );
    }

    static boolean shouldShowButton(
        boolean keyboardAvailable,
        boolean chatVisible,
        boolean connected,
        boolean openedChat
    ) {
        return keyboardAvailable && (chatVisible || connected && !openedChat);
    }

    private static boolean readVisible() {
        try {
            Field field = visibleField;
            if (field == null) {
                field = chatConsoleClass().getField("visible");
                visibleField = field;
            }
            return field.getBoolean(null);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return false;
        }
    }

    private static String readCurrentText() {
        try {
            Field field = currentTextField;
            if (field == null) {
                field = chatConsoleClass().getField("currentText");
                currentTextField = field;
            }
            Object value = field.get(null);
            return value == null ? "" : String.valueOf(value);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return "";
        }
    }

    private static boolean readOpenedChat() {
        try {
            Field field = openedChatField;
            if (field == null) {
                field = chatConsoleClass().getField("openedChat");
                openedChatField = field;
            }
            return field.getBoolean(null);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return true;
        }
    }

    private static boolean openChatConsole() {
        try {
            Method method = openMethod;
            if (method == null) {
                method = chatConsoleClass().getMethod("open");
                openMethod = method;
            }
            method.invoke(null);
            return readVisible();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isBase64Character(char character) {
        return character >= 'A' && character <= 'Z' ||
            character >= 'a' && character <= 'z' ||
            character >= '0' && character <= '9' ||
            character == '+' || character == '/' || character == '=';
    }

    private static void applyTextSync(Object chatConsole, String encodedText) {
        try {
            String text = new String(
                Base64.getDecoder().decode(encodedText),
                StandardCharsets.UTF_8
            );
            Method method = setTextMethod;
            if (method == null || method.getDeclaringClass() != chatConsole.getClass()) {
                method = chatConsole.getClass().getMethod("setText", String.class);
                setTextMethod = method;
            }
            method.invoke(chatConsole, text);
        } catch (ReflectiveOperationException | IllegalArgumentException | LinkageError ignored) {
        }
    }

    private static Class<?> chatConsoleClass() throws ClassNotFoundException {
        return Class.forName(
            CHAT_CONSOLE_CLASS,
            false,
            TogetherInSpireChatKeyboardButtonPatches.class.getClassLoader()
        );
    }

    private static void layoutHitbox() {
        float size = BUTTON_SIZE * Settings.scale;
        keyboardHitbox.resize(size, size);
        keyboardHitbox.move(
            (BUTTON_X + BUTTON_SIZE / 2.0f) * Settings.scale,
            (BUTTON_Y + BUTTON_SIZE / 2.0f) * Settings.scale
        );
    }

    private static boolean containsPointer(Hitbox hitbox) {
        return InputHelper.mX >= hitbox.x
            && InputHelper.mX <= hitbox.x + hitbox.width
            && InputHelper.mY >= hitbox.y
            && InputHelper.mY <= hitbox.y + hitbox.height;
    }

    private static void loadTexture() {
        if (keyboardTexture == null) {
            keyboardTexture = ImageMaster.loadImage(KEYBOARD_ICON);
        }
    }

    private static void renderButton(SpriteBatch sb) {
        Color previous = sb.getColor().cpy();
        sb.setColor(BUTTON_BORDER);
        sb.draw(
            ImageMaster.WHITE_SQUARE_IMG,
            keyboardHitbox.x,
            keyboardHitbox.y,
            keyboardHitbox.width,
            keyboardHitbox.height
        );
        float border = 1.5f * Settings.scale;
        sb.setColor(BUTTON_BACKGROUND);
        sb.draw(
            ImageMaster.WHITE_SQUARE_IMG,
            keyboardHitbox.x + border,
            keyboardHitbox.y + border,
            keyboardHitbox.width - border * 2.0f,
            keyboardHitbox.height - border * 2.0f
        );
        if (keyboardTexture != null) {
            float iconInset = 8.0f * Settings.scale;
            sb.setColor(Color.WHITE);
            sb.draw(
                keyboardTexture,
                keyboardHitbox.x + iconInset,
                keyboardHitbox.y + iconInset,
                keyboardHitbox.width - iconInset * 2.0f,
                keyboardHitbox.height - iconInset * 2.0f
            );
        }
        sb.setColor(previous);
    }
}
