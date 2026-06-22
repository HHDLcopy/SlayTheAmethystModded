package io.stamethyst.agent.monitors.impl;

import io.stamethyst.agent.channel.AgentDataChannel;
import io.stamethyst.agent.monitors.MonitorCapability;
import org.junit.After;
import org.junit.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Tests for {@link PlayMonitorAgent} mode switching and command dispatch.
 */
public class PlayMonitorAgentModeTest {

    @After
    public void cleanup() {
        PlayMonitorAgent inst = PlayMonitorAgent.INSTANCE;
        if (inst != null) {
            inst.detach();
        }
    }

    @Test
    public void defaultMode_isAutonomous() {
        PlayMonitorAgent agent = new PlayMonitorAgent();
        agent.attach();
        try {
            assertEquals(AgentPlayMode.AUTONOMOUS, agent.getMode());
        } finally {
            agent.detach();
        }
    }

    @Test
    public void mode_switchesToCommandDriven() {
        PlayMonitorAgent agent = new PlayMonitorAgent();
        agent.attach();
        try {
            // Enqueue MODE_COMMAND and consume it via pollAndExecute
            agent.execute("MODE_COMMAND", "{\"mode\":\"COMMAND_DRIVEN\"}");
            CommandQueue.QueuedPlayCommand cmd = agent.pollAndExecute();
            assertNotNull("command should be dequeued", cmd);
            assertEquals(PlayCommand.MODE_COMMAND, cmd.command);
            assertEquals(AgentPlayMode.COMMAND_DRIVEN, agent.getMode());
        } finally {
            agent.detach();
        }
    }

    @Test
    public void mode_switchesBackToAutonomous() {
        PlayMonitorAgent agent = new PlayMonitorAgent();
        agent.attach();
        try {
            agent.execute("MODE_COMMAND", "{\"mode\":\"COMMAND_DRIVEN\"}");
            agent.pollAndExecute();
            assertEquals(AgentPlayMode.COMMAND_DRIVEN, agent.getMode());

            agent.execute("MODE_COMMAND", "{\"mode\":\"AUTONOMOUS\"}");
            agent.pollAndExecute();
            assertEquals(AgentPlayMode.AUTONOMOUS, agent.getMode());
        } finally {
            agent.detach();
        }
    }

    @Test
    public void mode_resetsToAutonomousOnAttach() {
        PlayMonitorAgent agent = new PlayMonitorAgent();
        agent.attach();
        agent.execute("MODE_COMMAND", "{\"mode\":\"COMMAND_DRIVEN\"}");
        agent.pollAndExecute();
        assertEquals(AgentPlayMode.COMMAND_DRIVEN, agent.getMode());
        agent.detach();

        agent.attach();
        try {
            assertEquals(AgentPlayMode.AUTONOMOUS, agent.getMode());
        } finally {
            agent.detach();
        }
    }

    @Test
    public void mode_ignoresInvalidJson() {
        PlayMonitorAgent agent = new PlayMonitorAgent();
        agent.attach();
        try {
            AgentPlayMode before = agent.getMode();
            agent.execute("MODE_COMMAND", "not json}");
            agent.pollAndExecute();
            assertEquals("mode should not change on invalid input", before, agent.getMode());
        } finally {
            agent.detach();
        }
    }

    @Test
    public void otherCommands_exist() {
        PlayMonitorAgent agent = new PlayMonitorAgent();
        agent.attach();
        try {
            // PLAY_CARD should not throw
            agent.execute("PLAY_CARD", "{}");
            CommandQueue.QueuedPlayCommand cmd = agent.pollAndExecute();
            assertNotNull(cmd);
        } finally {
            agent.detach();
        }
    }
}
