package io.stamethyst.agent.monitors;

import io.stamethyst.agent.channel.AgentDataChannel;

import java.lang.instrument.Instrumentation;
import java.util.Set;

public interface Monitor {

    void attach(Instrumentation inst, String agentArgs, AgentDataChannel channel);

    void detach();

    String status();

    Set<MonitorCapability> capabilities();
}
