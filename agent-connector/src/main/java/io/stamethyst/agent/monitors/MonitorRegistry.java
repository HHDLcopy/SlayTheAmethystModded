package io.stamethyst.agent.monitors;

import io.stamethyst.agent.channel.AgentDataChannel;

import java.lang.instrument.Instrumentation;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MonitorRegistry {

    public interface MonitorFactory {
        Monitor create(Instrumentation inst, String argsJson, AgentDataChannel channel);
    }

    private final Map<String, MonitorFactory> factories = new ConcurrentHashMap<String, MonitorFactory>();

    public void register(String typePrefix, MonitorFactory factory) {
        factories.put(typePrefix, factory);
    }

    public Monitor create(String spec, Instrumentation inst, AgentDataChannel channel) {
        ParsedSpec parsed = parseSpec(spec);
        MonitorFactory factory = factories.get(parsed.prefix);
        if (factory == null) {
            throw new IllegalArgumentException("unknown monitor type: " + parsed.prefix);
        }
        return factory.create(inst, parsed.argsJson, channel);
    }

    public Set<String> registeredTypes() {
        return new HashSet<String>(factories.keySet());
    }

    public ParsedSpec parseSpec(String spec) {
        int atIndex = spec.indexOf('@');
        if (atIndex < 0) {
            return new ParsedSpec(spec, "{}");
        }
        String prefix = spec.substring(0, atIndex);
        String options = spec.substring(atIndex + 1);

        StringBuilder json = new StringBuilder("{");
        String[] pairs = options.split("@");
        for (int i = 0; i < pairs.length; i++) {
            String pair = pairs[i];
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = pair.substring(0, eq);
            String value = pair.substring(eq + 1);

            if (i > 0) json.append(',');
            json.append('"').append(key).append("\":");

            if (value.contains(",") || value.startsWith("[") || value.startsWith("{")) {
                if (value.startsWith("[") || value.startsWith("{")) {
                    json.append(value);
                } else {
                    json.append('[');
                    String[] items = value.split(",");
                    for (int j = 0; j < items.length; j++) {
                        if (j > 0) json.append(',');
                        json.append('"').append(items[j]).append('"');
                    }
                    json.append(']');
                }
            } else {
                json.append('"').append(value).append('"');
            }
        }
        json.append('}');
        return new ParsedSpec(prefix, json.toString());
    }

    public static class ParsedSpec {
        public final String prefix;
        public final String argsJson;

        public ParsedSpec(String prefix, String argsJson) {
            this.prefix = prefix;
            this.argsJson = argsJson;
        }
    }
}
