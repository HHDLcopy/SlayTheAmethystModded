package io.stamethyst.probe.monitors;

import io.stamethyst.probe.channel.AgentDataChannel;
import org.junit.Test;

import java.lang.instrument.Instrumentation;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class MonitorRegistryTest {

    private MonitorRegistry registry = new MonitorRegistry();

    @Test
    public void parseSpecNoOptions() {
        MonitorRegistry.ParsedSpec parsed = registry.parseSpec("tracing");
        assertEquals("tracing", parsed.prefix);
        assertEquals("{}", parsed.argsJson);
    }

    @Test
    public void parseSpecWithOptions() {
        MonitorRegistry.ParsedSpec parsed = registry.parseSpec("tracing@classes=com.a.*,com.b.*@methods=render");
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
            return new Monitor() {
                @Override public void attach(Instrumentation inst, String agentArgs, AgentDataChannel channel) {}
                @Override public void detach() {}
                @Override public String status() { return "ok"; }
                @Override public Set<MonitorCapability> capabilities() { return EnumSet.of(MonitorCapability.TRACING); }
            };
        });

        Monitor agent = registry.create("mock", null, null);
        assertNotNull(agent);
        assertTrue(created.get());
        assertEquals(EnumSet.of(MonitorCapability.TRACING), agent.capabilities());
    }

    @Test
    public void registerMultipleMonitors() {
        registry.register("tracing", (inst, args, channel) -> new StubMonitor("tracing", MonitorCapability.TRACING));
        registry.register("state", (inst, args, channel) -> new StubMonitor("state", MonitorCapability.TRACING));
        registry.register("tracing2", (inst, args, channel) -> new StubMonitor("gc", MonitorCapability.TRACING));

        assertNotNull(registry.create("tracing", null, null));
        assertNotNull(registry.create("tracing2", null, null));
    }

    @Test(expected = IllegalArgumentException.class)
    public void createUnknownMonitor() {
        registry.create("nonexistent", null, null);
    }

    @Test
    public void listRegisteredTypes() {
        registry.register("tracing", (inst, args, channel) -> new StubMonitor("t", MonitorCapability.TRACING));
        registry.register("tracing2", (inst, args, channel) -> new StubMonitor("s", MonitorCapability.TRACING));

        Set<String> types = registry.registeredTypes();
        assertTrue(types.contains("tracing"));
        assertTrue(types.contains("state"));
        assertEquals(2, types.size());
    }

    @Test
    public void duplicateRegisterReplace() {
        AtomicBoolean secondCalled = new AtomicBoolean(false);
        registry.register("dup", (inst, args, channel) -> new StubMonitor("old", MonitorCapability.TRACING));
        registry.register("dup", (inst, args, channel) -> {
            secondCalled.set(true);
            return new StubMonitor("new", MonitorCapability.TRACING);
        });

        Monitor agent = registry.create("dup", null, null);
        assertNotNull(agent);
        assertTrue(secondCalled.get());
    }

    private static class StubMonitor implements Monitor {
        private final String name;
        private final MonitorCapability cap;
        StubMonitor(String name, MonitorCapability cap) { this.name = name; this.cap = cap; }
        @Override public void attach(Instrumentation inst, String agentArgs, AgentDataChannel channel) {}
        @Override public void detach() {}
        @Override public String status() { return name; }
        @Override public Set<MonitorCapability> capabilities() { return EnumSet.of(cap); }
    }
}
