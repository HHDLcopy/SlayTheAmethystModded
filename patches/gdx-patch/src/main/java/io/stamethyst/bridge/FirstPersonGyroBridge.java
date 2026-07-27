package io.stamethyst.bridge;

import com.badlogic.gdx.Gdx;
import org.lwjgl.glfw.CallbackBridge;

/**
 * Supplies a virtual cursor for FirstPersonView's camera-only cursor reads.
 * The regular LibGDX input processor still receives the real pointer events.
 */
public final class FirstPersonGyroBridge {
    private static final float PIXELS_PER_RADIAN = 480.0f;
    private static final float DEFAULT_DELTA_SECONDS = 1.0f / 60.0f;

    private static float offsetX;
    private static float offsetY;
    private static boolean cursorXUpdated;

    private FirstPersonGyroBridge() {
    }

    /** Returns the centered virtual cursor's X coordinate. */
    public static int getCursorX(Object ignoredInput) {
        int width = 0;
        try {
            width = viewportWidth();
            int height = viewportHeight();
            advance(width, height);
            cursorXUpdated = true;
            return toCoordinate(width, offsetX);
        } catch (LinkageError | RuntimeException ignored) {
            resetOffsets();
            cursorXUpdated = false;
            return centerCoordinate(width);
        }
    }

    /** Returns the centered virtual cursor's Y coordinate. */
    public static int getCursorY(Object ignoredInput) {
        int height = 0;
        try {
            int width = viewportWidth();
            height = viewportHeight();
            // update() reads X immediately before Y. Reusing the state keeps one
            // gyro sample and one delta-time integration per renderer frame.
            if (!cursorXUpdated) {
                advance(width, height);
            }
            cursorXUpdated = false;
            return toCoordinate(height, offsetY);
        } catch (LinkageError | RuntimeException ignored) {
            resetOffsets();
            cursorXUpdated = false;
            return centerCoordinate(height);
        }
    }

    private static int viewportWidth() {
        return Gdx.graphics == null ? 0 : Gdx.graphics.getWidth();
    }

    private static int viewportHeight() {
        return Gdx.graphics == null ? 0 : Gdx.graphics.getHeight();
    }

    private static void advance(int width, int height) {
        float deltaSeconds = DEFAULT_DELTA_SECONDS;
        if (Gdx.graphics != null) {
            deltaSeconds = Gdx.graphics.getDeltaTime();
        }
        if (!(deltaSeconds > 0.0f) || Float.isNaN(deltaSeconds) ||
                Float.isInfinite(deltaSeconds)) {
            deltaSeconds = DEFAULT_DELTA_SECONDS;
        }
        // Avoid a large jump after a pause or a debugger break.
        deltaSeconds = Math.min(deltaSeconds, 0.1f);

        // Android reports angular velocity in radians per second. Yaw is the
        // device Y axis and pitch is the device X axis in the game view.
        offsetX += -CallbackBridge.nativeGetGyroscopeY() * deltaSeconds * PIXELS_PER_RADIAN;
        offsetY += CallbackBridge.nativeGetGyroscopeX() * deltaSeconds * PIXELS_PER_RADIAN;
        offsetX = clampOffset(offsetX, width);
        offsetY = clampOffset(offsetY, height);
    }

    private static float clampOffset(float offset, int dimension) {
        if (dimension <= 1) {
            return 0.0f;
        }
        float limit = dimension * 0.5f;
        if (Float.isNaN(offset) || Float.isInfinite(offset)) {
            return 0.0f;
        }
        return Math.max(-limit, Math.min(limit, offset));
    }

    private static int toCoordinate(int dimension, float offset) {
        if (dimension <= 1) {
            return 0;
        }
        int coordinate = Math.round(dimension * 0.5f + offset);
        return Math.max(0, Math.min(dimension - 1, coordinate));
    }

    private static int centerCoordinate(int dimension) {
        return dimension <= 0 ? 0 : dimension / 2;
    }

    private static void resetOffsets() {
        offsetX = 0.0f;
        offsetY = 0.0f;
    }
}
