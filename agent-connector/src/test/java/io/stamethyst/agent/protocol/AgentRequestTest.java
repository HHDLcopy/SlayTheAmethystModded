package io.stamethyst.agent.protocol;

import org.junit.Test;

import static org.junit.Assert.*;

public class AgentRequestTest {

    @Test
    public void parseAttachWithSpecAndArgs() {
        AgentRequest req = AgentRequest.parse("ATTACH tracing@classes=com.example.* {\"interval\":100}");
        assertEquals(AgentCommand.ATTACH, req.getCommand());
        assertEquals("tracing@classes=com.example.*", req.getSpec());
        assertEquals("{\"interval\":100}", req.getArgsJson());
        assertNull(req.getTarget());
    }

    @Test
    public void parseAttachWithSpecOnly() {
        AgentRequest req = AgentRequest.parse("ATTACH state@interval=500");
        assertEquals(AgentCommand.ATTACH, req.getCommand());
        assertEquals("state@interval=500", req.getSpec());
        assertEquals("{}", req.getArgsJson());
        assertNull(req.getTarget());
    }

    @Test
    public void parseAttachWithEmptyArgs() {
        AgentRequest req = AgentRequest.parse("ATTACH gc {}");
        assertEquals(AgentCommand.ATTACH, req.getCommand());
        assertEquals("gc", req.getSpec());
        assertEquals("{}", req.getArgsJson());
        assertNull(req.getTarget());
    }

    @Test
    public void parseAttachWithComplexJson() {
        AgentRequest req = AgentRequest.parse("ATTACH tracing {\"classes\":[\"a.*\",\"b.*\"],\"methods\":[\"foo\",\"bar\"]}");
        assertEquals(AgentCommand.ATTACH, req.getCommand());
        assertEquals("tracing", req.getSpec());
        assertEquals("{\"classes\":[\"a.*\",\"b.*\"],\"methods\":[\"foo\",\"bar\"]}", req.getArgsJson());
    }

    @Test
    public void parseDetach() {
        AgentRequest req = AgentRequest.parse("DETACH tracing-1");
        assertEquals(AgentCommand.DETACH, req.getCommand());
        assertEquals("tracing-1", req.getTarget());
        assertNull(req.getSpec());
        assertNull(req.getArgsJson());
    }

    @Test
    public void parseList() {
        AgentRequest req = AgentRequest.parse("LIST");
        assertEquals(AgentCommand.LIST, req.getCommand());
        assertNull(req.getTarget());
        assertNull(req.getSpec());
        assertNull(req.getArgsJson());
    }

    @Test
    public void parseStatus() {
        AgentRequest req = AgentRequest.parse("STATUS tracing-1");
        assertEquals(AgentCommand.STATUS, req.getCommand());
        assertEquals("tracing-1", req.getTarget());
        assertNull(req.getSpec());
        assertNull(req.getArgsJson());
    }

    @Test
    public void parseSubscribe() {
        AgentRequest req = AgentRequest.parse("SUBSCRIBE tracing-1");
        assertEquals(AgentCommand.SUBSCRIBE, req.getCommand());
        assertEquals("tracing-1", req.getTarget());
    }

    @Test
    public void parseUnsubscribe() {
        AgentRequest req = AgentRequest.parse("UNSUBSCRIBE tracing-1");
        assertEquals(AgentCommand.UNSUBSCRIBE, req.getCommand());
        assertEquals("tracing-1", req.getTarget());
    }

    @Test
    public void parseQuit() {
        AgentRequest req = AgentRequest.parse("QUIT");
        assertEquals(AgentCommand.QUIT, req.getCommand());
        assertNull(req.getTarget());
    }

    @Test
    public void parseTrimmingWhitespace() {
        AgentRequest req = AgentRequest.parse("  LIST  ");
        assertEquals(AgentCommand.LIST, req.getCommand());
    }

    @Test(expected = IllegalArgumentException.class)
    public void parseDetachWithoutTarget() {
        AgentRequest.parse("DETACH");
    }

    @Test(expected = IllegalArgumentException.class)
    public void parseAttachWithoutSpec() {
        AgentRequest.parse("ATTACH");
    }

    @Test(expected = IllegalArgumentException.class)
    public void parseEmptyLine() {
        AgentRequest.parse("");
    }
}
