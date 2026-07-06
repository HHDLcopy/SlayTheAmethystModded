package io.stamethyst.probe.util;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link AgentCrashLocals}.
 */
public class AgentCrashLocalsTest {

    @Test
    public void toJsonValue_null() {
        assertEquals("null", AgentCrashLocals.toJsonValue(null));
    }

    @Test
    public void toJsonValue_string() {
        assertEquals("\"hello world\"", AgentCrashLocals.toJsonValue("hello world"));
    }

    @Test
    public void toJsonValue_int() {
        assertEquals("42", AgentCrashLocals.toJsonValue(42));
    }

    @Test
    public void toJsonValue_double() {
        assertEquals("3.14", AgentCrashLocals.toJsonValue(3.14));
    }

    @Test
    public void toJsonValue_boolean() {
        assertEquals("true", AgentCrashLocals.toJsonValue(true));
        assertEquals("false", AgentCrashLocals.toJsonValue(false));
    }

    @Test
    public void dumpLocalTable_producesValidJson() {
        String[] names = {"this", "p", "m", "varStr", "varInt", "nullVal", "boolVal"};
        Object[] values = {"TestCard", "playerRef", null, "hello", 42, null, true};

        String json = AgentCrashLocals.dumpLocalTable(names, values);
        assertNotNull(json);
        assertTrue(json.startsWith("{"));
        assertTrue(json.endsWith("}"));

        assertTrue(json.contains("\"this\":\"TestCard\""));
        assertTrue(json.contains("\"varStr\":\"hello\""));
        assertTrue(json.contains("\"varInt\":42"));
        assertTrue(json.contains("\"nullVal\":null"));
        assertTrue(json.contains("\"m\":null"));
        assertTrue(json.contains("\"boolVal\":true"));
        assertTrue(json.contains("\"p\":\"playerRef\""));
    }

    @Test
    public void dumpLocalTable_escapesQuotes() {
        String[] names = {"x"};
        Object[] values = {"a\"b"};

        String json = AgentCrashLocals.dumpLocalTable(names, values);
        assertTrue(json.contains("\\\""));
    }

    @Test(expected = IllegalArgumentException.class)
    public void dumpLocalTable_rejectsMismatchedLengths() {
        AgentCrashLocals.dumpLocalTable(new String[]{"a"}, new Object[]{"x", "y"});
    }

    @Test
    public void dumpLocalTable_emptyArrays() {
        String json = AgentCrashLocals.dumpLocalTable(new String[0], new Object[0]);
        assertEquals("{}", json);
    }
}
