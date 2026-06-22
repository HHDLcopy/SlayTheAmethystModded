package io.stamethyst.agent.channel;

import io.stamethyst.agent.monitors.MonitorCapability;

import java.util.Set;

public interface AgentDataChannel {

    void send(String agentId, String jsonPayload);

    String getAgentId();

    Set<MonitorCapability> getCapabilities();
}
