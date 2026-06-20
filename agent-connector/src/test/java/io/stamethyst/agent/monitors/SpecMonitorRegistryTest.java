package io.stamethyst.agent.monitors;

import io.stamethyst.agent.channel.AgentDataChannel;
import org.junit.Test;

import java.lang.instrument.Instrumentation;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class SpecMonitorRegistryTest {

    private SpecMonitorRegistry registry = new SpecMonitorRegistry();

    @Test
    public void parseSpecNoOptions() {
        SpecMonitorRegistry.ParsedSpec parsed = registry.parseSpec("tracing");
        assertEquals("tracing", parsed.prefix);
        assertEquals("{}", parsed.argsJson);
    }

    @Test
    public void parseSpecWithOptions() {
        SpecMonitorRegistry.ParsedSpec parsed = registry.parseSpec("tracing@classes=com.a.*,com.b.*@methods=render");
        assertEquals("tracing", parsed.prefix);
        assertTrue(parsed.argsJson.contains("\"classes\""));
        assertTrue(parsed.argsJson.contains("com.a.*"));
        assertTrue(parsed.argsJson.contains("com.b.*"));
        assertTrue(parsed.argsJson.contains("\"methods\""));
        assertTrue(parsed.argsJson.contains("render"));
    }

    @Test
    public void registerAndCreateMonitor() {
        AtomicBoolean created = new AtomicBoolean(false);
        registry.register("mock", (inst, args, channel) -> {
            created.set(true);
            return new MonitorAgent() {
                @Override public void attach(Instrumentation inst, String agentArgs, AgentDataChannel channel) {}
                @Override public void detach() {}
                @Override public String status() { return "ok"; }
                @Override public Set<MonitorCapability> capabilities() { return EnumSet.of(MonitorCapability.STATE); }
            };
        });

        MonitorAgent agent = registry.create("mock", null, null);
        assertNotNull(agent);
        assertTrue(created.get());
        assertEquals(EnumSet.of(MonitorCapability.STATE), agent.capabilities());
    }

    @Test
    public void registerMultipleMonitors() {
        registry.register("tracing", (inst, args, channel) -> new StubMonitor("tracing", MonitorCapability.TRACING));
        registry.register("state", (inst, args, channel) -> new StubMonitor("state", MonitorCapability.STATE));
        registry.register("gc", (inst, args, channel) -> new StubMonitor("gc", MonitorCapability.GC));

        assertNotNull(registry.create("tracing", null, null));
        assertNotNull(registry.create("state", null, null));
        assertNotNull(registry.create("gc", null, null));
    }

    @Test(expected = IllegalArgumentException.class)
    public void createUnknownMonitor() {
        registry.create("nonexistent", null, null);
    }

    @Test
    public void listRegisteredTypes() {
        registry.register("tracing", (inst, args, channel) -> new StubMonitor("t", MonitorCapability.TRACING));
        registry.register("state", (inst, args, channel) -> new StubMonitor("s", MonitorCapability.STATE));

        Set<String> types = registry.registeredTypes();
        assertTrue(types.contains("tracing"));
        assertTrue(types.contains("state"));
        assertEquals(2, types.size());
    }

    @Test
    public void duplicateRegisterReplace() {
        AtomicBoolean secondCalled = new AtomicBoolean(false);
        registry.register("dup", (inst, args, channel) -> new StubMonitor("old", MonitorCapability.STATE));
        registry.register("dup", (inst, args, channel) -> {
            secondCalled.set(true);
            return new StubMonitor("new", MonitorCapability.TRACING);
        });

        MonitorAgent agent = registry.create("dup", null, null);
        assertNotNull(agent);
        assertTrue(secondCalled.get());
    }

    private static class StubMonitor implements MonitorAgent {
        private final String name;
        private final MonitorCapability cap;
        StubMonitor(String name, MonitorCapability cap) { this.name = name; this.cap = cap; }
        @Override public void attach(Instrumentation inst, String agentArgs, AgentDataChannel channel) {}
        @Override public void detach() {}
        @Override public String status() { return name; }
        @Override public Set<MonitorCapability> capabilities() { return EnumSet.of(cap); }
    }
}
