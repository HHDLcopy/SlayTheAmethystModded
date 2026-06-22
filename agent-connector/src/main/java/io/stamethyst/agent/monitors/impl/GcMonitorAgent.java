package io.stamethyst.agent.monitors.impl;

import io.stamethyst.agent.channel.AgentDataChannel;
import io.stamethyst.agent.monitors.MonitorAgent;
import io.stamethyst.agent.monitors.MonitorCapability;

import java.lang.instrument.Instrumentation;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class GcMonitorAgent implements MonitorAgent {

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
        return EnumSet.of(MonitorCapability.GC);
    }

    public String collectGcStats() {
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"gc_beans\":[");
        for (int i = 0; i < gcBeans.size(); i++) {
            if (i > 0) sb.append(",");
            GarbageCollectorMXBean bean = gcBeans.get(i);
            sb.append("{\"name\":\"").append(escapeJson(bean.getName())).append("\"");
            sb.append(",\"collections\":").append(bean.getCollectionCount());
            sb.append(",\"time_ms\":").append(bean.getCollectionTime());
            sb.append(",\"pool_names\":[");
            String[] pools = bean.getMemoryPoolNames();
            for (int j = 0; j < pools.length; j++) {
                if (j > 0) sb.append(",");
                sb.append("\"").append(escapeJson(pools[j])).append("\"");
            }
            sb.append("]}");
        }
        sb.append("]}");
        return sb.toString();
    }

    public String collectHeapStats() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memoryBean.getNonHeapMemoryUsage();

        StringBuilder sb = new StringBuilder();
        sb.append("{\"heap_used\":").append(heap.getUsed());
        sb.append(",\"heap_max\":").append(heap.getMax());
        sb.append(",\"heap_committed\":").append(heap.getCommitted());
        sb.append(",\"nonheap_used\":").append(nonHeap.getUsed());
        sb.append(",\"nonheap_committed\":").append(nonHeap.getCommitted());

        Runtime runtime = Runtime.getRuntime();
        sb.append(",\"runtime_free\":").append(runtime.freeMemory());
        sb.append(",\"runtime_total\":").append(runtime.totalMemory());
        sb.append(",\"runtime_max\":").append(runtime.maxMemory());
        sb.append("}");

        return sb.toString();
    }

    public void sendSnapshot() {
        if (!attached || channel == null) return;

        String gcStats = collectGcStats();
        String heapStats = collectHeapStats();

        String combined = "{" +
            "\"gc\":" + gcStats + "," +
            "\"heap\":" + heapStats +
            "}";

        channel.send(channel.getAgentId(), combined);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
