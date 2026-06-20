package io.stamethyst.agent.monitors;

import io.stamethyst.agent.channel.AgentDataChannel;
import io.stamethyst.agent.monitors.impl.ThreadMonitorAgent;
import org.junit.Test;

import java.lang.instrument.Instrumentation;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class ThreadMonitorAgentTest {

    @Test
    public void collectThreadDump() {
        StubChannel channel = new StubChannel("thread-test");
        ThreadMonitorAgent agent = new ThreadMonitorAgent();

        agent.attach(null, "{}", channel);
        assertNotNull(agent.status());

        String dump = agent.collectThreadDump();
        assertNotNull(dump);
        assertTrue(dump.contains("thread"));
    }

    @Test
    public void collectThreadDumpWithDeadlockDetection() {
        StubChannel channel = new StubChannel("thread-test");
        ThreadMonitorAgent agent = new ThreadMonitorAgent();

        agent.attach(null, "{}", channel);
        String dump = agent.collectThreadDump();
        assertTrue(dump.contains("\"deadlocked_threads\""));
    }

    @Test
    public void capabilities() {
        ThreadMonitorAgent agent = new ThreadMonitorAgent();
        Set<MonitorCapability> caps = agent.capabilities();
        assertEquals(1, caps.size());
        assertTrue(caps.contains(MonitorCapability.THREAD));
    }

    @Test
    public void detachCleansUp() {
        StubChannel channel = new StubChannel("thread-test");
        ThreadMonitorAgent agent = new ThreadMonitorAgent();
        agent.attach(null, "{}", channel);
        agent.detach();
        assertNotNull(agent.status());
    }

    private static class StubChannel implements AgentDataChannel {
        private final String id;
        StubChannel(String id) { this.id = id; }
        @Override public void send(String agentId, String jsonPayload) {}
        @Override public String getAgentId() { return id; }
        @Override public Set<MonitorCapability> getCapabilities() { return EnumSet.noneOf(MonitorCapability.class); }
    }
}
