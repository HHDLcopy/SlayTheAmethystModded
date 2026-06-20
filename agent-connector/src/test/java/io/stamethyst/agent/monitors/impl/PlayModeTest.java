package io.stamethyst.agent.monitors.impl;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link AgentPlayMode} and {@link PlayCommand} mode-related changes.
 */
public class PlayModeTest {

    @Test
    public void agentPlayMode_fromString_autonomous() {
        assertEquals(AgentPlayMode.AUTONOMOUS, AgentPlayMode.fromString("AUTONOMOUS"));
        assertEquals(AgentPlayMode.AUTONOMOUS, AgentPlayMode.fromString("autonomous"));
        assertEquals(AgentPlayMode.AUTONOMOUS, AgentPlayMode.fromString("  AUTONOMOUS  "));
    }

    @Test
    public void agentPlayMode_fromString_commandDriven() {
        assertEquals(AgentPlayMode.COMMAND_DRIVEN, AgentPlayMode.fromString("COMMAND_DRIVEN"));
        assertEquals(AgentPlayMode.COMMAND_DRIVEN, AgentPlayMode.fromString("command_driven"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void agentPlayMode_fromString_null() {
        AgentPlayMode.fromString(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void agentPlayMode_fromString_empty() {
        AgentPlayMode.fromString("");
    }

    @Test(expected = IllegalArgumentException.class)
    public void agentPlayMode_fromString_invalid() {
        AgentPlayMode.fromString("BOGUS_MODE");
    }

    @Test
    public void playCommand_hasModeCommand() {
        assertEquals(PlayCommand.MODE_COMMAND, PlayCommand.fromString("MODE_COMMAND"));
        assertEquals(PlayCommand.MODE_COMMAND, PlayCommand.fromString("mode_command"));
    }

    @Test
    public void playCommand_hasAllExistingCommands() {
        assertNotNull(PlayCommand.fromString("PLAY_CARD"));
        assertNotNull(PlayCommand.fromString("PLAY_CARD_TARGETED"));
        assertNotNull(PlayCommand.fromString("END_TURN"));
        assertNotNull(PlayCommand.fromString("PRESS_PROCEED"));
        assertNotNull(PlayCommand.fromString("SELECT_MAP_NODE"));
        assertNotNull(PlayCommand.fromString("SELECT_BOSS"));
        assertNotNull(PlayCommand.fromString("SKIP_ROOM"));
        assertNotNull(PlayCommand.fromString("CHOOSE_CHARACTER"));
        assertNotNull(PlayCommand.fromString("EMBARK"));
        assertNotNull(PlayCommand.fromString("RETURN_TO_MENU"));
        assertNotNull(PlayCommand.fromString("WAIT"));
    }
}
