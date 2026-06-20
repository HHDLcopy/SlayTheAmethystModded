package io.stamethyst.agent.monitors.impl;

import io.stamethyst.agent.channel.AgentDataChannel;
import io.stamethyst.agent.monitors.MonitorAgent;
import io.stamethyst.agent.monitors.MonitorCapability;

import java.lang.instrument.Instrumentation;
import java.util.*;

public class ClassMonitorAgent implements MonitorAgent {

    private AgentDataChannel channel;
    private Instrumentation instrumentation;
    private boolean attached;
    private Map<String, Long> previousClassCount = new HashMap<String, Long>();

    @Override
    public void attach(Instrumentation inst, String agentArgs, AgentDataChannel channel) {
        this.instrumentation = inst;
        this.channel = channel;
        this.attached = true;
        previousClassCount.clear();
    }

    @Override
    public void detach() {
        attached = false;
        previousClassCount.clear();
    }

    @Override
    public String status() {
        if (!attached) return "detached";
        if (instrumentation != null) {
            return "active:" + instrumentation.getAllLoadedClasses().length;
        }
        return "active";
    }

    @Override
    public Set<MonitorCapability> capabilities() {
        return EnumSet.of(MonitorCapability.CLASS);
    }

    public String summarizeLoadedClasses(Instrumentation inst) {
        if (inst == null) {
            return "{\"loaded_class_count\":0,\"packages\":{}}";
        }

        Class<?>[] classes = inst.getAllLoadedClasses();
        Map<String, Long> pkgCounts = new TreeMap<String, Long>();

        for (Class<?> cls : classes) {
            if (cls == null) continue;
            String name = cls.getName();
            if (name == null) continue;

            String pkg = "";
            int lastDot = name.lastIndexOf('.');
            if (lastDot > 0) {
                pkg = name.substring(0, lastDot);
            }

            Long count = pkgCounts.get(pkg);
            pkgCounts.put(pkg, count != null ? count + 1 : 1);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"loaded_class_count\":").append(classes.length);
        sb.append(",\"packages\":{");
        boolean first = true;
        for (Map.Entry<String, Long> entry : pkgCounts.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":").append(entry.getValue());
            first = false;
        }
        sb.append("}}");

        return sb.toString();
    }

    public void sendSnapshot() {
        if (!attached || channel == null) return;
        String summary = instrumentation != null ?
            summarizeLoadedClasses(instrumentation) : "{\"loaded_class_count\":0}";
        channel.send(channel.getAgentId(), summary);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
