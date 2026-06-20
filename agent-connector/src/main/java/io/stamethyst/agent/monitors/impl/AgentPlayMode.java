package io.stamethyst.agent.monitors.impl;

/**
 * Play mode controlled by the agent via MODE_COMMAND.
 *
 * <p>When the play monitor is attached the default mode is {@link #AUTONOMOUS}:
 * the autoplay driver continues making autonomous decisions and the agent can
 * OBSERVE without interfering.  Sending
 * {@code EXEC MODE_COMMAND {"mode":"COMMAND_DRIVEN"}} switches to
 * {@link #COMMAND_DRIVEN}, where the autoplay tick only consumes the command
 * queue and never performs autonomous actions.
 */
public enum AgentPlayMode {
    AUTONOMOUS,
    COMMAND_DRIVEN;

    public static AgentPlayMode fromString(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("empty play mode");
        }
        return valueOf(text.trim().toUpperCase());
    }
}
