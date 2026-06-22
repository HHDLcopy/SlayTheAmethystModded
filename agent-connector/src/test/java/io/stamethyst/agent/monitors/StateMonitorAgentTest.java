package io.stamethyst.agent.monitors;

import io.stamethyst.agent.channel.AgentDataChannel;
import io.stamethyst.agent.monitors.impl.StateMonitorAgent;
import org.junit.Test;

import java.lang.instrument.Instrumentation;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.Assert.*;

public class StateMonitorAgentTest {

    @Test
    public void capabilities() {
        StateMonitorAgent agent = new StateMonitorAgent();
        Set<MonitorCapability> caps = agent.capabilities();
        assertEquals(1, caps.size());
        assertTrue(caps.contains(MonitorCapability.STATE));
    }

    @Test
    public void reflectFieldValue() {
        String value = StateMonitorAgent.reflectFieldValue(System.class, "out", null);
        assertNotNull(value);
    }

    @Test
    public void reflectFieldValueWithNonNullInstance() {
        String testStr = "hello";
        String value = StateMonitorAgent.reflectFieldValue(String.class, "value", testStr);
        assertNotNull(value);
    }

    @Test
    public void reflectFieldValueForMissingField() {
        String value = StateMonitorAgent.reflectFieldValue(String.class, "nonexistent_field", null);
        assertNull(value);
    }

    @Test
    public void reflectFieldValueForMissingClass() {
        String value = StateMonitorAgent.reflectFieldValue(null, "anyField", null);
        assertNull(value);
    }

    @Test
    public void attachAndDetach() {
        StubChannel channel = new StubChannel("state-test");
        StateMonitorAgent agent = new StateMonitorAgent();
        agent.attach(null, "{}", channel);
        assertNotNull(agent.status());
        agent.detach();
    }

    private static class StubChannel implements AgentDataChannel {
        private final String id;
        StubChannel(String id) { this.id = id; }
        @Override public void send(String agentId, String jsonPayload) {}
        @Override public String getAgentId() { return id; }
        @Override public Set<MonitorCapability> getCapabilities() { return EnumSet.noneOf(MonitorCapability.class); }
    }
}
