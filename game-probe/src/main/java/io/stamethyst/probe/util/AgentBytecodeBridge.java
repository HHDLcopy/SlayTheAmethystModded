package io.stamethyst.probe.util;

import io.stamethyst.probe.channel.AgentDataChannel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AgentBytecodeBridge {

    private static final Map<String, AgentDataChannel> CHANNELS = new ConcurrentHashMap<String, AgentDataChannel>();
    private static final Map<String, TracingPerfSink> PERF_SINKS = new ConcurrentHashMap<String, TracingPerfSink>();

    public interface TracingPerfSink {
        void recordEvent(long durationNs);
    }

    private AgentBytecodeBridge() {}

    public static void registerChannel(String agentId, AgentDataChannel channel) {
        CHANNELS.put(agentId, channel);
    }

    public static void unregisterChannel(String agentId) {
        CHANNELS.remove(agentId);
        PERF_SINKS.remove(agentId);
    }

    public static void registerPerfSink(String agentId, TracingPerfSink sink) {
        PERF_SINKS.put(agentId, sink);
    }

    public static void onMethodEntry(String agentId, String className, String methodName, long timestamp) {
        AgentDataChannel ch = CHANNELS.get(agentId);
        if (ch == null) return;
        try {
            String json = "{\"type\":\"method_entry\",\"class\":\"" + escapeJson(className) +
                    "\",\"method\":\"" + escapeJson(methodName) +
                    "\",\"ts\":" + timestamp + "}";
            ch.send(agentId, json);
        } catch (Exception ignored) {
        }
    }

    public static void onMethodExit(String agentId, String className, String methodName, long timestamp, long durationNs) {
        AgentDataChannel ch = CHANNELS.get(agentId);
        if (ch != null) {
            try {
                String json = "{\"type\":\"method_exit\",\"class\":\"" + escapeJson(className) +
                        "\",\"method\":\"" + escapeJson(methodName) +
                        "\",\"ts\":" + timestamp +
                        ",\"duration_ns\":" + durationNs + "}";
                ch.send(agentId, json);
            } catch (Exception ignored) {
            }
        }
        TracingPerfSink sink = PERF_SINKS.get(agentId);
        if (sink != null) {
            sink.recordEvent(durationNs);
        }
    }

    public static void onMethodException(String agentId, String className, String methodName,
                                          long timestamp, String exceptionType, String exceptionMessage,
                                          String localsJson) {
        AgentDataChannel ch = CHANNELS.get(agentId);
        if (ch == null) return;
        try {
            String json = "{\"type\":\"method_exception\",\"class\":\"" + escapeJson(className) +
                    "\",\"method\":\"" + escapeJson(methodName) +
                    "\",\"ts\":" + timestamp +
                    ",\"exception_type\":\"" + escapeJson(exceptionType) +
                    "\",\"exception_message\":\"" + escapeJson(exceptionMessage) +
                    "\",\"locals\":" + localsJson + "}";
            ch.send(agentId, json);
        } catch (Exception ignored) {
        }
    }

    /**
     * Simplified crash-locals bridge: called from injected bytecode with the
     * raw exception and parallel name/value arrays.  The bridge handles
     * exception-type/message extraction and local-table JSON building so
     * the catch-handler bytecode stays linear (no StringBuilder / GOTO).
     */
    public static void onMethodExceptionSimple(
            String agentId, String className, String methodName,
            long timestamp, Throwable exception,
            String[] names, Object[] values) {
        AgentDataChannel ch = CHANNELS.get(agentId);
        if (ch == null) return;
        try {
            String exType = exception.getClass().getName();
            String exMsg = exception.getMessage();
            if (exMsg == null) exMsg = "";
            String localsJson = AgentCrashLocals.dumpLocalTable(names, values);
            String json = "{\"type\":\"method_exception\",\"class\":\"" + escapeJson(className) +
                    "\",\"method\":\"" + escapeJson(methodName) +
                    "\",\"ts\":" + timestamp +
                    ",\"exception_type\":\"" + escapeJson(exType) +
                    "\",\"exception_message\":\"" + escapeJson(exMsg) +
                    "\",\"locals\":" + localsJson + "}";
            ch.send(agentId, json);
        } catch (Exception ignored) {
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
