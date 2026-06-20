package io.stamethyst.agent.protocol;

public final class AgentResponse {

    private AgentResponse() {}

    public static String ok() {
        return "OK";
    }

    public static String ok(String agentId) {
        return "OK " + agentId;
    }

    public static String error(String message) {
        return "ERROR " + message;
    }

    public static String error(String format, Object... args) {
        return "ERROR " + String.format(format, args);
    }

    public static String agents(String[] ids, String[] specs, String[] states) {
        if (ids.length != specs.length || ids.length != states.length) {
            throw new IllegalArgumentException("arrays must have equal length");
        }
        StringBuilder sb = new StringBuilder("AGENTS");
        for (int i = 0; i < ids.length; i++) {
            sb.append(' ').append(ids[i]).append(':').append(specs[i]).append(':').append(states[i]);
        }
        return sb.toString();
    }

    public static String status(String agentId, String state, long uptimeMs, int eventCount) {
        return "STATUS " + agentId + " " + state + " " + uptimeMs + " " + eventCount;
    }

    public static String data(String agentId, String jsonPayload) {
        return "DATA " + agentId + " " + jsonPayload;
    }

    public static String bye() {
        return "BYE";
    }

    public static String result(String json) {
        return "RESULT " + json;
    }

    public static String state(String json) {
        return "STATE " + json;
    }

    public static String perf(String json) {
        return "PERF " + json;
    }

    public static String bytecodeHeader() {
        return "BYTECODE ";
    }

    public static boolean isError(String responseLine) {
        return responseLine != null && responseLine.startsWith("ERROR ");
    }
}
