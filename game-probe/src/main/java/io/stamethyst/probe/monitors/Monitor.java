package io.stamethyst.probe.monitors;

import io.stamethyst.probe.channel.AgentDataChannel;

import java.lang.instrument.Instrumentation;
import java.util.Set;

public interface Monitor {

    void attach(Instrumentation inst, String agentArgs, AgentDataChannel channel);

    void detach();

    String status();

    Set<MonitorCapability> capabilities();
}
