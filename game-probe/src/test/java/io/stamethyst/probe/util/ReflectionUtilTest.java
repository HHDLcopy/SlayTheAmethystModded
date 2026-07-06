package io.stamethyst.probe.util;

import io.stamethyst.probe.GameProbe;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.Assert.*;

/**
 * Tests for {@link ReflectionUtil} ClassLoader bridging and diagnostics.
 */
public class ReflectionUtilTest {

    private final ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
    private PrintStream originalErr;

    @Before
    public void captureStderr() {
        originalErr = System.err;
        System.setErr(new PrintStream(capturedErr));
    }

    @After
    public void restoreStderr() {
        System.setErr(originalErr);
    }

    @Test
    public void forName_returnsNullAndLogsForNonexistentClass() {
        ClassLoader saved = GameProbe.GAME_CLASSLOADER;
        GameProbe.GAME_CLASSLOADER = null;
        try {
            Class<?> result = ReflectionUtil.forName("com.nonexistent.DoesNotExistXYZ");
            assertNull(result);
            String logged = capturedErr.toString();
            assertTrue("should log failure", logged.contains("ReflectionUtil"));
            assertTrue("should contain FQCN", logged.contains("com.nonexistent.DoesNotExistXYZ"));
        } finally {
            GameProbe.GAME_CLASSLOADER = saved;
        }
    }

    @Test
    public void forName_nullInput_returnsNullNoLog() {
        assertNull(ReflectionUtil.forName(null));
        assertNull(ReflectionUtil.forName(""));
        assertEquals("", capturedErr.toString());
    }

    @Test
    public void getStaticField_logsWhenClassNotFound() {
        ClassLoader saved = GameProbe.GAME_CLASSLOADER;
        GameProbe.GAME_CLASSLOADER = null;
        try {
            Object result = ReflectionUtil.getStaticField("com.nonexistent.Bogus", "field");
            assertNull(result);
            assertTrue("should log class-not-found", capturedErr.toString().contains("class not found"));
        } finally {
            GameProbe.GAME_CLASSLOADER = saved;
        }
    }

    @Test
    public void getStaticField_logsWhenFieldNotFound() {
        Object result = ReflectionUtil.getStaticField("java.lang.String", "nonexistentXYZField");
        assertNull(result);
        assertTrue("should log field-not-found", capturedErr.toString().contains("field not found"));
    }

    @Test
    public void getStaticField_returnsValueForRealField() {
        Object result = ReflectionUtil.getStaticField("java.lang.String", "CASE_INSENSITIVE_ORDER");
        assertNotNull(result);
        assertEquals("", capturedErr.toString());
    }
}
