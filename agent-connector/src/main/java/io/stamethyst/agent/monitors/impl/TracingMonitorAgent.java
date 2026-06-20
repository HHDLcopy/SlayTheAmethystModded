package io.stamethyst.agent.monitors.impl;

import io.stamethyst.agent.channel.AgentDataChannel;
import io.stamethyst.agent.monitors.MonitorAgent;
import io.stamethyst.agent.monitors.MonitorCapability;
import io.stamethyst.agent.util.AgentBytecodeBridge;
import io.stamethyst.agent.util.AsmMethodInterceptor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

public class TracingMonitorAgent implements MonitorAgent, AgentBytecodeBridge.TracingPerfSink {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private AgentDataChannel channel;
    private ClassFileTransformer transformer;
    private Instrumentation instrumentation;
    private String[] methodFilter;
    private String[] classPatterns;
    private boolean attached;
    private boolean captureLocals;
    private final AtomicLong totalEvents = new AtomicLong(0);
    private final AtomicLong totalDurationNs = new AtomicLong(0);
    private long perfStartMs = 0;
    private long perfStartEvents = 0;
    private long perfStartDurationNs = 0;

    @Override
    public void attach(Instrumentation inst, String agentArgs, AgentDataChannel channel) {
        this.channel = channel;
        this.instrumentation = inst;
        this.attached = true;

        parseArgs(agentArgs, channel.getAgentId());

        AgentBytecodeBridge.registerChannel(channel.getAgentId(), channel);

        if (channel != null) {
            AgentBytecodeBridge.registerPerfSink(channel.getAgentId(), this);
        }

        if (inst != null && classPatterns != null) {
            transformer = new TracingTransformer(classPatterns, methodFilter, channel, captureLocals);
            try {
                inst.addTransformer(transformer, true);
                for (Class<?> cls : inst.getAllLoadedClasses()) {
                    if (matchesAnyPattern(cls.getName(), classPatterns) && inst.isModifiableClass(cls)) {
                        try { inst.retransformClasses(cls); } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable e) {
                // retransform not supported — only new classes will be traced
                inst.addTransformer(transformer, false);
            }
        }
    }

    @Override
    public void detach() {
        attached = false;
        if (channel != null) {
            AgentBytecodeBridge.unregisterChannel(channel.getAgentId());
        }
        if (instrumentation != null && transformer != null) {
            instrumentation.removeTransformer(transformer);
        }
        transformer = null;
        instrumentation = null;
    }

    @Override
    public String status() {
        return attached ? "active" : "detached";
    }

    /** Reset perf counters and record baseline. */
    public void perfStart() {
        perfStartMs = System.currentTimeMillis();
        perfStartEvents = totalEvents.get();
        perfStartDurationNs = totalDurationNs.get();
    }

    /** Compute delta stats since last perfStart. */
    public String perfStop() {
        long now = System.currentTimeMillis();
        long elapsedMs = now - perfStartMs;
        long events = totalEvents.get() - perfStartEvents;
        long ns = totalDurationNs.get() - perfStartDurationNs;
        long avgNs = events > 0 ? (ns / events) : 0;
        double rate = elapsedMs > 0 ? (events * 1000.0 / elapsedMs) : 0;
        return "{\"elapsed_ms\":" + elapsedMs
            + ",\"events\":" + events
            + ",\"total_ns\":" + ns
            + ",\"avg_ns\":" + avgNs
            + ",\"rate_per_sec\":" + String.format("%.1f", rate)
            + "}";
    }

    /** Called from AgentBytecodeBridge to record timing. */
    public void recordEvent(long durationNs) {
        totalEvents.incrementAndGet();
        if (durationNs > 0) {
            totalDurationNs.addAndGet(durationNs);
        }
    }

    @Override
    public Set<MonitorCapability> capabilities() {
        return EnumSet.of(MonitorCapability.TRACING);
    }

    private void parseArgs(String argsJson, String agentId) {
        if (argsJson.contains("\"classes\"")) {
            String classesPart = extractJsonValue(argsJson, "classes");
            if (classesPart != null) {
                classPatterns = classesPart.split(",");
                for (int i = 0; i < classPatterns.length; i++) {
                    classPatterns[i] = classPatterns[i].replace("\"", "").trim();
                }
            }
        }
        if (argsJson.contains("\"methods\"")) {
            String methodsPart = extractJsonValue(argsJson, "methods");
            if (methodsPart != null) {
                methodFilter = methodsPart.split(",");
                for (int i = 0; i < methodFilter.length; i++) {
                    methodFilter[i] = methodFilter[i].replace("\"", "").trim();
                }
            }
        }
        if (argsJson.contains("\"locals\"")) {
            String localsPart = extractJsonValue(argsJson, "locals");
            captureLocals = "true".equalsIgnoreCase(localsPart);
        }
    }

    private static String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\":";
        int idx = json.indexOf(searchKey);
        if (idx < 0) return null;
        int valueStart = json.indexOf('"', idx + searchKey.length());
        if (valueStart < 0) return null;
        char next = json.charAt(valueStart);
        if (next == '"') {
            int valueEnd = json.indexOf('"', valueStart + 1);
            if (valueEnd < 0) return null;
            return json.substring(valueStart + 1, valueEnd);
        } else if (next == '[') {
            int valueEnd = json.indexOf(']', valueStart + 1);
            if (valueEnd < 0) return null;
            return json.substring(valueStart + 1, valueEnd);
        }
        return null;
    }

    public static boolean matchesPattern(String className, String pattern) {
        String regex = "^" + pattern.replace(".", "\\.").replace("*", ".*") + "$";
        return Pattern.compile(regex).matcher(className).matches();
    }

    public static boolean matchesAnyPattern(String className, String[] patterns) {
        for (String pattern : patterns) {
            if (matchesPattern(className, pattern)) return true;
        }
        return false;
    }

    public static boolean matchesMethod(String methodName, String[] methodFilter) {
        if (methodFilter == null) return true;
        for (String m : methodFilter) {
            if (m.equals(methodName)) return true;
        }
        return false;
    }

    private static class TracingTransformer implements ClassFileTransformer {
        private final String[] classPatterns;
        private final String[] methodFilter;
        private final AgentDataChannel channel;
        private final boolean captureLocals;

        TracingTransformer(String[] classPatterns, String[] methodFilter, AgentDataChannel channel,
                           boolean captureLocals) {
            this.classPatterns = classPatterns;
            this.methodFilter = methodFilter;
            this.channel = channel;
            this.captureLocals = captureLocals;
        }

        @Override
        public byte[] transform(ClassLoader loader, String internalName, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            // Capture the game ClassLoader on first sight
            if (loader != null && io.stamethyst.agent.AgentConnector.GAME_CLASSLOADER == null
                && !internalName.startsWith("io/stamethyst/agent/")
                && !internalName.startsWith("java/")
                && !internalName.startsWith("sun/")
                && !internalName.startsWith("jdk/")) {
                io.stamethyst.agent.AgentConnector.GAME_CLASSLOADER = loader;
            }
            String className = internalName.replace('/', '.');
            if (!matchesAnyPattern(className, classPatterns)) {
                return null;
            }

            try {
                ClassReader reader = new ClassReader(classfileBuffer);
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
                AsmMethodInterceptor interceptor = new AsmMethodInterceptor(writer, className, methodFilter, channel, captureLocals);
                reader.accept(interceptor, ClassReader.EXPAND_FRAMES);
                return writer.toByteArray();
            } catch (Exception e) {
                return null;
            }
        }
    }
}
