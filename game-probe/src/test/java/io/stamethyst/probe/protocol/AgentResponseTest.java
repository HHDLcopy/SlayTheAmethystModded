package io.stamethyst.probe.protocol;

import org.junit.Test;

import static org.junit.Assert.*;

public class AgentResponseTest {

    @Test
    public void okResponse() {
        assertEquals("OK", AgentResponse.ok());
    }

    @Test
    public void okWithAgentId() {
        assertEquals("OK tracing-1", AgentResponse.ok("tracing-1"));
    }

    @Test
    public void errorResponse() {
        assertEquals("ERROR message", AgentResponse.error("message"));
    }

    @Test
    public void errorWithArguments() {
        assertEquals("ERROR unknown agent: tracing-1", AgentResponse.error("unknown agent: %s", "tracing-1"));
    }

    @Test
    public void agentsListEmpty() {
        assertEquals("MONITORS", AgentResponse.agents(new String[0], new String[0], new String[0]));
    }

    @Test
    public void agentsListWithEntries() {
        String line = AgentResponse.agents(
            new String[]{"tracing-1", "state-1", "gc-2"},
            new String[]{"tracing", "state", "gc"},
            new String[]{"active", "active", "active"}
        );
        assertEquals("MONITORS tracing-1:tracing:active state-1:state:active gc-2:gc:active", line);
    }

    @Test
    public void agentsListMismatchedLengths() {
        try {
            AgentResponse.agents(
                new String[]{"tracing-1"},
                new String[]{"tracing", "state"},
                new String[]{"active", "active"}
            );
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    @Test
    public void statusResponse() {
        String line = AgentResponse.status("tracing-1", "active", 12345L, 42);
        assertEquals("STATUS tracing-1 active 12345 42", line);
    }

    @Test
    public void statusResponseWithZeroEvents() {
        String line = AgentResponse.status("gc-2", "active", 0L, 0);
        assertEquals("STATUS gc-2 active 0 0", line);
    }

    @Test
    public void dataResponse() {
        String line = AgentResponse.data("tracing-1", "{\"type\":\"method_entry\"}");
        assertEquals("DATA tracing-1 {\"type\":\"method_entry\"}", line);
    }

    @Test
    public void byeResponse() {
        assertEquals("BYE", AgentResponse.bye());
    }

    @Test
    public void isErrorResponse() {
        assertTrue(AgentResponse.isError("ERROR something"));
        assertFalse(AgentResponse.isError("OK"));
        assertFalse(AgentResponse.isError("DATA x {}"));
    }
}
