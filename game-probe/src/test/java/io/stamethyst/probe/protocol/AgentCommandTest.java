package io.stamethyst.probe.protocol;

import org.junit.Test;

import static org.junit.Assert.*;

public class AgentCommandTest {

    @Test
    public void parseAttach() {
        assertEquals(AgentCommand.ATTACH, AgentCommand.parse("ATTACH"));
    }

    @Test
    public void parseDetach() {
        assertEquals(AgentCommand.DETACH, AgentCommand.parse("DETACH"));
    }

    @Test
    public void parseList() {
        assertEquals(AgentCommand.LIST, AgentCommand.parse("LIST"));
    }

    @Test
    public void parseStatus() {
        assertEquals(AgentCommand.STATUS, AgentCommand.parse("STATUS"));
    }

    @Test
    public void parseSubscribe() {
        assertEquals(AgentCommand.SUBSCRIBE, AgentCommand.parse("SUBSCRIBE"));
    }

    @Test
    public void parseUnsubscribe() {
        assertEquals(AgentCommand.UNSUBSCRIBE, AgentCommand.parse("UNSUBSCRIBE"));
    }

    @Test
    public void parseQuit() {
        assertEquals(AgentCommand.QUIT, AgentCommand.parse("QUIT"));
    }

    @Test
    public void parseCaseInsensitive() {
        assertEquals(AgentCommand.ATTACH, AgentCommand.parse("attach"));
        assertEquals(AgentCommand.LIST, AgentCommand.parse("List"));
    }

    @Test
    public void parseWithTrailingWhitespace() {
        assertEquals(AgentCommand.STATUS, AgentCommand.parse("STATUS "));
        assertEquals(AgentCommand.QUIT, AgentCommand.parse(" QUIT"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void parseUnknownCommand() {
        AgentCommand.parse("UNKNOWN");
    }

    @Test(expected = IllegalArgumentException.class)
    public void parseEmptyString() {
        AgentCommand.parse("");
    }

    @Test(expected = IllegalArgumentException.class)
    public void parseNull() {
        AgentCommand.parse(null);
    }
}
