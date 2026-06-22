package io.stamethyst.agent.util;

public final class AgentCrashLocals {

    private AgentCrashLocals() {}

    /**
     * Convert any local variable value (boxed primitive or Object) to a JSON-safe string.
     * This is called from injected bytecode in catch handlers.
     */
    public static String toJsonValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "\"" + escapeJson((String) value) + "\"";
        }
        if (value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Boolean) {
            return value.toString();
        }
        return "\"" + escapeJson(String.valueOf(value)) + "\"";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    /**
     * Build a JSON object string from parallel arrays of local variable names
     * and their values.  Called from catch-blocks injected via bytecode.
     *
     * @param names  local variable names (e.g. "varStr", "varInt", ...)
     * @param values boxed values matching each name (null → JSON null)
     * @return a JSON object string, e.g. {"varStr":"hello","varInt":42}
     */
    public static String dumpLocalTable(String[] names, Object[] values) {
        if (names.length != values.length) {
            throw new IllegalArgumentException(
                "names.length=" + names.length + " != values.length=" + values.length);
        }
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        for (int i = 0; i < names.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escapeJson(names[i])).append("\":");
            sb.append(toJsonValue(values[i]));
        }
        sb.append('}');
        return sb.toString();
    }
}
