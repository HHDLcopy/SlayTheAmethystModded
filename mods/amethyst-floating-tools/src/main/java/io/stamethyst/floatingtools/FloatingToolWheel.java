package io.stamethyst.floatingtools;

final class FloatingToolWheel {
    private static final float DEAD_ZONE = 0.16f;
    private static final long REPEAT_SLOW_MS = 88L;
    private static final long REPEAT_FAST_MS = 42L;
    private static final int MIN_SCROLL_DELTA = 48;
    private static final int MAX_SCROLL_DELTA = 132;
    private boolean pressed;
    private float normalizedOffset;
    private long nextRepeatAtMs;
    private int lastDirection;

    void begin(float offset) {
        pressed = true;
        lastDirection = 0;
        updateOffset(offset, true);
    }

    void update(float offset) {
        if (!pressed) {
            return;
        }
        updateOffset(offset, false);
    }

    void end() {
        pressed = false;
        normalizedOffset = 0f;
        lastDirection = 0;
    }

    boolean isActive() {
        return pressed;
    }

    float normalizedOffset() {
        return normalizedOffset;
    }

    private void updateOffset(float offset, boolean force) {
        normalizedOffset = clamp(offset, -1f, 1f);
        int direction = currentDirection();
        if (direction == 0) {
            lastDirection = 0;
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && lastDirection == direction && now < nextRepeatAtMs) {
            return;
        }
        FloatingToolInputBridge.sendScroll(direction * currentScrollDelta());
        nextRepeatAtMs = now + currentRepeatDelayMs();
        lastDirection = direction;
    }

    private int currentDirection() {
        if (normalizedOffset > DEAD_ZONE) {
            return 1;
        }
        if (normalizedOffset < -DEAD_ZONE) {
            return -1;
        }
        return 0;
    }

    private int currentScrollDelta() {
        float strength = normalizedStrength();
        return Math.round(MIN_SCROLL_DELTA + (MAX_SCROLL_DELTA - MIN_SCROLL_DELTA) * strength);
    }

    private long currentRepeatDelayMs() {
        float strength = normalizedStrength();
        long delayRange = REPEAT_SLOW_MS - REPEAT_FAST_MS;
        long delay = REPEAT_SLOW_MS - Math.round(delayRange * strength);
        if (delay < REPEAT_FAST_MS) {
            return REPEAT_FAST_MS;
        }
        return Math.min(delay, REPEAT_SLOW_MS);
    }

    private float normalizedStrength() {
        float strength = (Math.abs(normalizedOffset) - DEAD_ZONE) / (1f - DEAD_ZONE);
        return clamp(strength, 0f, 1f);
    }

    private static float clamp(float value, float min, float max) {
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }
}
