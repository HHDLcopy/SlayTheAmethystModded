package io.stamethyst.agent.channel;

import io.stamethyst.agent.monitors.MonitorCapability;
import io.stamethyst.agent.protocol.AgentResponse;

import java.io.PrintWriter;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class TcpDataChannel implements AgentDataChannel {

    private final PrintWriter writer;
    private final String agentId;
    private final Set<MonitorCapability> capabilities;
    private final AtomicBoolean subscribed = new AtomicBoolean(false);

    public TcpDataChannel(PrintWriter writer, String agentId, Set<MonitorCapability> capabilities) {
        this.writer = writer;
        this.agentId = agentId;
        this.capabilities = capabilities;
    }

    public void setSubscribed(boolean value) {
        subscribed.set(value);
    }

    @Override
    public void send(String agentId, String jsonPayload) {
        if (!subscribed.get()) return;
        try {
            writer.println(AgentResponse.data(agentId, jsonPayload));
            writer.flush();
        } catch (Exception ignored) {
        }
    }

    @Override
    public String getAgentId() {
        return agentId;
    }

    @Override
    public Set<MonitorCapability> getCapabilities() {
        return capabilities;
    }
}
