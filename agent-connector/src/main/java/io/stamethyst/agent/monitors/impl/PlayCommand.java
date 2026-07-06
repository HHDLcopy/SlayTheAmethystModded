package io.stamethyst.agent.monitors.impl;

/**
 * Commands the PlayMonitor can execute.
 * Each command may accept a JSON params object with additional detail.
 */
public enum PlayCommand {
    PLAY_CARD,
    PLAY_CARD_TARGETED,
    END_TURN,
    PRESS_PROCEED,
    SELECT_MAP_NODE,
    SELECT_BOSS,
    SKIP_ROOM,
    CHOOSE_CHARACTER,
    EMBARK,
    RETURN_TO_MENU,
    WAIT,
    MODE_COMMAND;

    public static PlayCommand fromString(String text) {
        String trimmed = text == null ? "" : text.trim().toUpperCase();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("empty play command");
        }
        return valueOf(trimmed);
    }
}
