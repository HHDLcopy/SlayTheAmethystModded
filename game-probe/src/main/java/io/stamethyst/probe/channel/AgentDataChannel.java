package io.stamethyst.probe.channel;

import io.stamethyst.probe.monitors.MonitorCapability;

import java.util.Set;

public interface AgentDataChannel {

    void send(String agentId, String jsonPayload);

    String getAgentId();

    Set<MonitorCapability> getCapabilities();
}
