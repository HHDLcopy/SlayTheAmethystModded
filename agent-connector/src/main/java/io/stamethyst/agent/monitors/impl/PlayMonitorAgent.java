package io.stamethyst.agent.monitors.impl;

import io.stamethyst.agent.channel.AgentDataChannel;
import io.stamethyst.agent.monitors.MonitorAgent;
import io.stamethyst.agent.monitors.MonitorCapability;

import java.lang.instrument.Instrumentation;
import java.util.EnumSet;
import java.util.Set;

/**
 * Interactive play monitor.  When attached, it replaces the autonomous
 * autoplay decision loop with a command-driven model:
 *
 *  1. The AutoplayDriver tick no longer makes decisions autonomously.
 *     Instead it calls {@link #pollAndExecute()} which dequeues from
 *     the {@link CommandQueue} and runs the command on the game thread.
 *
 *  2. The {@code OBSERVE} protocol command returns a game-state snapshot.
 *
 *  3. The {@code EXEC} protocol command pushes a {@link PlayCommand} onto
 *     the queue.
 *
 * A single VM-wide instance is stored in {@link #INSTANCE} so other game
 * threads can reference it without Spring/dependency-injection.
 */
public class PlayMonitorAgent implements MonitorAgent {

    public static PlayMonitorAgent INSTANCE;

    /**
     * Current play mode visible to {@link AutoplayDriver}.
     * Changed via {@code EXEC MODE_COMMAND {"mode":"..."}}.
     */
    private volatile AgentPlayMode mode = AgentPlayMode.AUTONOMOUS;

    private AgentDataChannel channel;
    private boolean attached;

    @Override
    public void attach(Instrumentation inst, String agentArgs, AgentDataChannel channel) {
        this.channel = channel;
        this.attached = true;
        this.mode = AgentPlayMode.AUTONOMOUS;
        INSTANCE = this;
        CommandQueue.clear();
    }

    /** Attach without instrumentation (for testing / agentmain without retransform). */
    void attach() {
        this.attached = true;
        this.mode = AgentPlayMode.AUTONOMOUS;
        INSTANCE = this;
        CommandQueue.clear();
    }

    @Override
    public void detach() {
        attached = false;
        INSTANCE = null;
        CommandQueue.clear();
        channel = null;
    }

    @Override
    public String status() {
        return attached ? "active" : "detached";
    }

    @Override
    public Set<MonitorCapability> capabilities() {
        return EnumSet.noneOf(MonitorCapability.class);
    }

    /** Returns the current play mode for {@link AutoplayDriver}. */
    public AgentPlayMode getMode() {
        return mode;
    }

    // ── Called from AgentSession ──────────────────────────────────

    public String observe() {
        return GameStateSnapshot.buildSnapshot();
    }

    public String execute(String commandText, String paramsJson) {
        try {
            PlayCommand cmd = PlayCommand.fromString(commandText);
            CommandQueue.enqueue(cmd, paramsJson != null ? paramsJson : "{}");
            return "{\"queued\":true,\"command\":\"" + escape(cmd.name()) + "\",\"queueSize\":" + CommandQueue.size() + "}";
        } catch (Exception e) {
            return "{\"queued\":false,\"error\":\"" + escape(e.getMessage()) + "\"}";
        }
    }

    // ── Called from AutoplayDriver tick ───────────────────────────

    /**
     * Poll the command queue and execute one command on the game thread.
     * Returns null if no command was available, or the command that was executed.
     */
    public CommandQueue.QueuedPlayCommand pollAndExecute() {
        CommandQueue.QueuedPlayCommand cmd = CommandQueue.pollNow();
        if (cmd == null) return null;
        try {
            executeGameCommand(cmd.command, cmd.paramsJson);
        } catch (Exception e) {
            if (channel != null) {
                try {
                    String json = "{\"type\":\"exec_error\",\"command\":\"" + escape(cmd.command.name()) +
                        "\",\"error\":\"" + escape(e.getMessage()) + "\"}";
                    channel.send(channel.getAgentId(), json);
                } catch (Exception ignored) {}
            }
        }
        return cmd;
    }

    private void executeGameCommand(PlayCommand cmd, String paramsJson) {
        try {
            switch (cmd) {
                case PLAY_CARD:
                    AutoplayHook.playRandomCard();
                    break;
                case END_TURN:
                    AutoplayHook.endTurn();
                    break;
                case PRESS_PROCEED:
                    AutoplayHook.pressProceed();
                    break;
                case SKIP_ROOM:
                    AutoplayHook.skipRoom();
                    break;
                case WAIT:
                    try {
                        int ms = Math.min(extractIntParam(paramsJson, "ms", 100), 5000);
                        Thread.sleep(ms);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        Thread.sleep(100);
                    }
                    break;
                case MODE_COMMAND:
                    try {
                        AgentPlayMode newMode = extractModeParam(paramsJson);
                        if (newMode != null) {
                            this.mode = newMode;
                            if (channel != null) {
                                channel.send(channel.getAgentId(),
                                    "{\"type\":\"mode_changed\",\"mode\":\"" + newMode.name() + "\"}");
                            }
                        }
                    } catch (Exception ignored) {}
                    break;
                default:
                    // Unimplemented commands — log and continue
                    if (channel != null) {
                        String json = "{\"type\":\"exec_unsupported\",\"command\":\"" + escape(cmd.name()) + "\"}";
                        channel.send(channel.getAgentId(), json);
                    }
                    break;
            }
        } catch (Throwable t) {
            throw new RuntimeException("play command failed: " + cmd, t);
        }
    }

    // ── Interface

    @Override public String toString() { return "PlayMonitorAgent"; }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static int extractIntParam(String json, String key, int defaultValue) {
        if (json == null || json.isEmpty()) return defaultValue;
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return defaultValue;
        int start = idx + search.length();
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t')) start++;
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        if (end > start) {
            try { return Integer.parseInt(json.substring(start, end)); }
            catch (NumberFormatException e) { return defaultValue; }
        }
        return defaultValue;
    }

    private static AgentPlayMode extractModeParam(String json) {
        if (json == null || json.isEmpty()) return null;
        String search = "\"mode\":\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int start = idx + search.length();
        int end = json.indexOf('"', start);
        if (end < 0) return null;
        try {
            return AgentPlayMode.fromString(json.substring(start, end));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
