package io.stamethyst.floatingtools;

import com.badlogic.gdx.Gdx;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import org.lwjgl.glfw.CallbackBridge;
import org.lwjgl.input.GLFWInputImplementation;
import org.lwjgl.input.Keyboard;

final class FloatingToolInputBridge {
    static final int MOUSE_LEFT = 0;
    static final int MOUSE_RIGHT = 1;
    static final int MOUSE_WHEEL_EVENT = -1;

    private FloatingToolInputBridge() {
    }

    static void sendMouseButton(int button, boolean down) {
        int[] cursor = currentCursorForQueue();
        GLFWInputImplementation.singleton.putMouseEventWithCoords(
            (byte) button,
            (byte) (down ? 1 : 0),
            cursor[0],
            cursor[1],
            0,
            System.nanoTime()
        );
    }

    static void sendScroll(int dWheel) {
        if (dWheel == 0) {
            return;
        }
        int[] cursor = currentCursorForQueue();
        GLFWInputImplementation.singleton.putMouseEventWithCoords(
            (byte) MOUSE_WHEEL_EVENT,
            (byte) 0,
            cursor[0],
            cursor[1],
            dWheel,
            System.nanoTime()
        );
    }

    static void sendKey(int keyCode, boolean down) {
        sendKeyEvent(keyCode, down, 0, false);
    }

    static void sendKeyStroke(int keyCode, char typedChar) {
        sendKeyEvent(keyCode, true, typedChar, false);
        if (keyCode != Keyboard.KEY_NONE) {
            sendKeyEvent(keyCode, false, 0, false);
        }
    }

    static void sendTypedChar(char typedChar) {
        if (typedChar == 0) {
            return;
        }
        int keyCode = keyCodeForChar(typedChar);
        sendKeyStroke(keyCode, typedChar);
    }

    static void sendString(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\r') {
                continue;
            }
            if (ch == '\n') {
                sendKeyStroke(Keyboard.KEY_RETURN, '\n');
            } else if (ch == '\t') {
                sendKeyStroke(Keyboard.KEY_TAB, '\t');
            } else if (ch == '\b') {
                sendKeyStroke(Keyboard.KEY_BACK, '\b');
            } else {
                sendTypedChar(ch);
            }
        }
    }

    static String pasteClipboardText() {
        try {
            String text = CallbackBridge.nativeClipboard(CallbackBridge.CLIPBOARD_PASTE, null);
            return text == null ? "" : text;
        } catch (Throwable ignored) {
            return "";
        }
    }

    static void requestKeyboard(String source) {
        writeKeyboardRequest(source, false);
    }

    static void requestSystemKeyboard(String source) {
        writeKeyboardRequest(source, true);
    }

    static void requestCustomButton(String source) {
        String cleanSource = source == null || source.trim().isEmpty() ? "floating_tools" : source.trim();
        writeKeyboardRequest("custom_button:" + cleanSource, false);
    }

    static void requestOnlinePanel(String source) {
        String cleanSource = source == null || source.trim().isEmpty() ? "floating_tools" : source.trim();
        writeKeyboardRequest("online_panel:" + cleanSource, false);
    }

    private static void writeKeyboardRequest(String source, boolean forceSystemKeyboard) {
        String path = System.getProperty("amethyst.in_game_keyboard_request", "").trim();
        if (path.isEmpty()) {
            return;
        }
        File requestFile = new File(path);
        File parent = requestFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return;
        }
        FileWriter writer = null;
        try {
            writer = new FileWriter(requestFile, false);
            String cleanSource = source == null || source.trim().isEmpty() ? "floating_tools" : source.trim();
            if (forceSystemKeyboard && !cleanSource.startsWith("system_keyboard:")) {
                cleanSource = "system_keyboard:" + cleanSource;
            }
            writer.write(cleanSource);
            writer.write('\n');
            writer.write(Long.toString(System.currentTimeMillis()));
            writer.write('\n');
        } catch (IOException ignored) {
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    static int keyCodeForChar(char ch) {
        char lower = Character.toLowerCase(ch);
        switch (lower) {
            case 'a':
                return Keyboard.KEY_A;
            case 'b':
                return Keyboard.KEY_B;
            case 'c':
                return Keyboard.KEY_C;
            case 'd':
                return Keyboard.KEY_D;
            case 'e':
                return Keyboard.KEY_E;
            case 'f':
                return Keyboard.KEY_F;
            case 'g':
                return Keyboard.KEY_G;
            case 'h':
                return Keyboard.KEY_H;
            case 'i':
                return Keyboard.KEY_I;
            case 'j':
                return Keyboard.KEY_J;
            case 'k':
                return Keyboard.KEY_K;
            case 'l':
                return Keyboard.KEY_L;
            case 'm':
                return Keyboard.KEY_M;
            case 'n':
                return Keyboard.KEY_N;
            case 'o':
                return Keyboard.KEY_O;
            case 'p':
                return Keyboard.KEY_P;
            case 'q':
                return Keyboard.KEY_Q;
            case 'r':
                return Keyboard.KEY_R;
            case 's':
                return Keyboard.KEY_S;
            case 't':
                return Keyboard.KEY_T;
            case 'u':
                return Keyboard.KEY_U;
            case 'v':
                return Keyboard.KEY_V;
            case 'w':
                return Keyboard.KEY_W;
            case 'x':
                return Keyboard.KEY_X;
            case 'y':
                return Keyboard.KEY_Y;
            case 'z':
                return Keyboard.KEY_Z;
            default:
                break;
        }
        switch (ch) {
            case '1':
                return Keyboard.KEY_1;
            case '2':
                return Keyboard.KEY_2;
            case '3':
                return Keyboard.KEY_3;
            case '4':
                return Keyboard.KEY_4;
            case '5':
                return Keyboard.KEY_5;
            case '6':
                return Keyboard.KEY_6;
            case '7':
                return Keyboard.KEY_7;
            case '8':
                return Keyboard.KEY_8;
            case '9':
                return Keyboard.KEY_9;
            case '0':
                return Keyboard.KEY_0;
            case '-':
            case '_':
                return Keyboard.KEY_MINUS;
            case '=':
            case '+':
                return Keyboard.KEY_EQUALS;
            case '[':
            case '{':
                return Keyboard.KEY_LBRACKET;
            case ']':
            case '}':
                return Keyboard.KEY_RBRACKET;
            case ';':
            case ':':
                return Keyboard.KEY_SEMICOLON;
            case '\'':
            case '"':
                return Keyboard.KEY_APOSTROPHE;
            case '`':
            case '~':
                return Keyboard.KEY_GRAVE;
            case '\\':
            case '|':
                return Keyboard.KEY_BACKSLASH;
            case ',':
            case '<':
                return Keyboard.KEY_COMMA;
            case '.':
            case '>':
                return Keyboard.KEY_PERIOD;
            case '/':
            case '?':
                return Keyboard.KEY_SLASH;
            case ' ':
                return Keyboard.KEY_SPACE;
            default:
                return Keyboard.KEY_NONE;
        }
    }

    private static void sendKeyEvent(int keyCode, boolean down, int typedChar, boolean repeat) {
        GLFWInputImplementation.singleton.putKeyboardEvent(
            keyCode,
            (byte) (down ? 1 : 0),
            typedChar,
            System.nanoTime(),
            repeat
        );
    }

    private static int[] currentCursorForQueue() {
        int width = Math.max(1, Gdx.graphics.getWidth());
        int height = Math.max(1, Gdx.graphics.getHeight());
        int x = clamp(InputHelper.mX, 0, width - 1);
        int logicalY = clamp(InputHelper.mY, 0, height - 1);
        int queueY = height - 1 - logicalY;
        return new int[] {x, queueY};
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }
}
