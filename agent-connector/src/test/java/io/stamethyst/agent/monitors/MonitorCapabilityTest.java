package io.stamethyst.agent.monitors;

import org.junit.Test;

import static org.junit.Assert.*;

public class MonitorCapabilityTest {

    @Test
    public void allCapabilitiesExist() {
        assertEquals(6, MonitorCapability.values().length);
    }

    @Test
    public void capabilityNames() {
        assertEquals("TRACING", MonitorCapability.TRACING.name());
        assertEquals("STATE", MonitorCapability.STATE.name());
        assertEquals("THREAD", MonitorCapability.THREAD.name());
        assertEquals("GC", MonitorCapability.GC.name());
        assertEquals("CLASS", MonitorCapability.CLASS.name());
        assertEquals("BYTECODE", MonitorCapability.BYTECODE.name());
    }
}
