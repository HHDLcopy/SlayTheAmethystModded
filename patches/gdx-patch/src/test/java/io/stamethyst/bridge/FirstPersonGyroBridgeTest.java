package io.stamethyst.bridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FirstPersonGyroBridgeTest {
    @Test
    public void cursorDeltas_useMatchingDeviceAxes() {
        assertEquals(240.0f, FirstPersonGyroBridge.horizontalCursorDelta(2.0f, 0.25f), 0.0001f);
        assertEquals(-180.0f, FirstPersonGyroBridge.verticalCursorDelta(1.5f, 0.25f), 0.0001f);
    }
}
