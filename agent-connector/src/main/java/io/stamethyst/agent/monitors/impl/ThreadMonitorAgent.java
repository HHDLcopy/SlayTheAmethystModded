package io.stamethyst.agent.monitors.impl;

import io.stamethyst.agent.channel.AgentDataChannel;
import io.stamethyst.agent.monitors.MonitorAgent;
import io.stamethyst.agent.monitors.MonitorCapability;

import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.EnumSet;
import java.util.Set;

public class ThreadMonitorAgent implements MonitorAgent {

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
        return EnumSet.of(MonitorCapability.THREAD);
    }

    public String collectThreadDump() {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"thread_count\":").append(threadBean.getThreadCount());
        sb.append(",\"peak_thread_count\":").append(threadBean.getPeakThreadCount());
        sb.append(",\"daemon_thread_count\":").append(threadBean.getDaemonThreadCount());
        sb.append(",\"total_started_thread_count\":").append(threadBean.getTotalStartedThreadCount());

        long[] deadlockedThreads = threadBean.findDeadlockedThreads();
        sb.append(",\"deadlocked_threads\":");
        if (deadlockedThreads != null) {
            sb.append("[");
            for (int i = 0; i < deadlockedThreads.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(deadlockedThreads[i]);
            }
            sb.append("]");
        } else {
            sb.append("[]");
        }

        ThreadInfo[] threadInfos = threadBean.dumpAllThreads(true, true);
        sb.append(",\"threads\":[");
        for (int i = 0; i < threadInfos.length; i++) {
            if (i > 0) sb.append(",");
            ThreadInfo ti = threadInfos[i];
            sb.append("{\"name\":\"").append(escapeJson(ti.getThreadName())).append("\"");
            sb.append(",\"id\":").append(ti.getThreadId());
            sb.append(",\"state\":\"").append(ti.getThreadState().name()).append("\"");
            sb.append(",\"blocked_count\":").append(ti.getBlockedCount());
            sb.append(",\"waited_count\":").append(ti.getWaitedCount());
            sb.append(",\"cpu_time\":").append(threadBean.getThreadCpuTime(ti.getThreadId()));
            sb.append("}");
        }
        sb.append("]");
        sb.append("}");
        return sb.toString();
    }

    public void sendDump() {
        if (!attached || channel == null) return;
        String dump = collectThreadDump();
        channel.send(channel.getAgentId(), dump);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
