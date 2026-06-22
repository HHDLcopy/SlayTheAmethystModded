package io.stamethyst.agent.monitors;

import io.stamethyst.agent.channel.AgentDataChannel;
import io.stamethyst.agent.monitors.impl.GcMonitorAgent;
import org.junit.Test;

import java.lang.instrument.Instrumentation;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.Assert.*;

public class GcMonitorAgentTest {

    @Test
    public void collectGcStats() {
        StubChannel channel = new StubChannel("gc-test");
        GcMonitorAgent agent = new GcMonitorAgent();

        agent.attach(null, "{}", channel);
        String stats = agent.collectGcStats();
        assertNotNull(stats);
        assertTrue(stats.contains("\"gc_beans\""));
    }

    @Test
    public void collectHeapStats() {
        StubChannel channel = new StubChannel("gc-test");
        GcMonitorAgent agent = new GcMonitorAgent();

        agent.attach(null, "{}", channel);
        String stats = agent.collectHeapStats();
        assertNotNull(stats);
        assertTrue(stats.contains("\"heap_max\""));
    }

    @Test
    public void capabilities() {
        GcMonitorAgent agent = new GcMonitorAgent();
        Set<MonitorCapability> caps = agent.capabilities();
        assertEquals(1, caps.size());
        assertTrue(caps.contains(MonitorCapability.GC));
    }

    @Test
    public void detachCleansUp() {
        StubChannel channel = new StubChannel("gc-test");
        GcMonitorAgent agent = new GcMonitorAgent();
        agent.attach(null, "{}", channel);
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
