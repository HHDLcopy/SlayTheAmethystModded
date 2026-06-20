package io.stamethyst.agent.monitors.impl;

import io.stamethyst.agent.channel.AgentDataChannel;
import io.stamethyst.agent.monitors.MonitorAgent;
import io.stamethyst.agent.monitors.MonitorCapability;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.Set;

public class StateMonitorAgent implements MonitorAgent {

    private AgentDataChannel channel;
    private boolean attached;

    @Override
    public void attach(Instrumentation inst, String agentArgs, AgentDataChannel channel) {
        this.channel = channel;
        this.attached = true;
    }

    @Override
    public void detach() {
        attached = false;
    }

    @Override
    public String status() {
        return attached ? "active" : "detached";
    }

    @Override
    public Set<MonitorCapability> capabilities() {
        return EnumSet.of(MonitorCapability.STATE);
    }

    public void sendSnapshot() {
        if (!attached || channel == null) return;

        String snapshot = "{}";
        channel.send(channel.getAgentId(), snapshot);
    }

    public static String reflectFieldValue(Class<?> clazz, String fieldName, Object instance) {
        if (clazz == null) return null;
        try {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(instance);
            return value != null ? value.toString() : "null";
        } catch (Exception e) {
            return null;
        }
    }
}
