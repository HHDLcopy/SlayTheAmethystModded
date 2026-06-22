package io.stamethyst.agent.monitors.impl;

import io.stamethyst.agent.channel.AgentDataChannel;
import io.stamethyst.agent.monitors.MonitorAgent;
import io.stamethyst.agent.monitors.MonitorCapability;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

public class BytecodeDumpAgent implements MonitorAgent {

    private AgentDataChannel channel;
    private Instrumentation instrumentation;
    private ClassFileTransformer transformer;
    private boolean attached;

    @Override
    public void attach(Instrumentation inst, String agentArgs, AgentDataChannel channel) {
        this.channel = channel;
        this.instrumentation = inst;
        this.attached = true;

        String[] classes = parseClasses(agentArgs);

        if (inst != null && classes != null && classes.length > 0) {
            transformer = createDumpTransformer(channel);
            inst.addTransformer(transformer, true);

            for (Class<?> cls : inst.getAllLoadedClasses()) {
                if (cls == null) continue;
                if (matchesTarget(cls.getName(), classes) && inst.isModifiableClass(cls)) {
                    try {
                        inst.retransformClasses(cls);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    @Override
    public void detach() {
        attached = false;
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

    @Override
    public Set<MonitorCapability> capabilities() {
        return EnumSet.of(MonitorCapability.BYTECODE);
    }

    public static ClassFileTransformer createDumpTransformer(AgentDataChannel channel) {
        return new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String internalName, Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                String className = internalName.replace('/', '.');
                String b64 = Base64.getEncoder().encodeToString(classfileBuffer);
                String json = "{\"class\":\"" + escapeJson(className) + "\",\"bytecode\":\"" + b64 + "\"}";
                channel.send(channel.getAgentId(), json);
                return null;
            }
        };
    }

    static String[] parseClasses(String argsJson) {
        if (argsJson == null || argsJson.isEmpty()) {
            return null;
        }
        int keyIdx = argsJson.indexOf("\"classes\"");
        if (keyIdx < 0) {
            return null;
        }
        int colonIdx = argsJson.indexOf(':', keyIdx);
        if (colonIdx < 0) {
            return null;
        }
        int valStart = -1;
        for (int i = colonIdx + 1; i < argsJson.length(); i++) {
            char c = argsJson.charAt(i);
            if (c == '"' || c == '[') {
                valStart = i;
                break;
            }
        }
        if (valStart < 0) {
            return null;
        }
        char delim = argsJson.charAt(valStart);
        if (delim == '"') {
            int end = argsJson.indexOf('"', valStart + 1);
            if (end < 0) return null;
            return argsJson.substring(valStart + 1, end).split(",");
        } else if (delim == '[') {
            int end = argsJson.indexOf(']', valStart + 1);
            if (end < 0) return null;
            String inner = argsJson.substring(valStart + 1, end);
            String[] items = inner.split(",");
            for (int i = 0; i < items.length; i++) {
                items[i] = items[i].replace("\"", "").trim();
            }
            return items;
        }
        return null;
    }

    static boolean matchesTarget(String className, String[] targets) {
        for (String target : targets) {
            String t = target.trim().replace("\"", "");
            if (className.equals(t)) {
                return true;
            }
        }
        return false;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
