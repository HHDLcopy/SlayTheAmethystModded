package io.stamethyst.probe.monitors.impl;

import io.stamethyst.probe.util.ReflectionUtil;

/**
 * Builds a JSON snapshot of the current SlayTheSpire game state for OBSERVE.
 * All game-class access goes through {@link ReflectionUtil} which routes
 * through the captured MTS ClassLoader.
 */
public final class GameStateSnapshot {

    private GameStateSnapshot() {}

    public static String buildSnapshot() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{");
        addMode(sb);
        sb.append(",");
        addScreen(sb);
        sb.append(",");
        addRoom(sb);
        sb.append(",");
        addCombat(sb);
        sb.append(",");
        addMap(sb);
        sb.append("}");
        return sb.toString();
    }

    private static void addMode(StringBuilder sb) {
        sb.append("\"mode\":\"");
        try {
            Object mode = ReflectionUtil.getStaticField(
                "com.megacrit.cardcrawl.core.CardCrawlGame", "mode");
            sb.append(String.valueOf(mode));
        } catch (Throwable t) {
            sb.append("UNKNOWN");
        }
        sb.append("\"");
    }

    private static void addScreen(StringBuilder sb) {
        sb.append("\"screen\":\"");
        try {
            Object screen = ReflectionUtil.getStaticField(
                "com.megacrit.cardcrawl.dungeons.AbstractDungeon", "screen");
            sb.append(String.valueOf(screen));
            sb.append("\",\"isScreenUp\":");
            Object isUp = ReflectionUtil.getStaticField(
                "com.megacrit.cardcrawl.dungeons.AbstractDungeon", "isScreenUp");
            sb.append(String.valueOf(isUp != null ? isUp : false));
        } catch (Throwable t) {
            sb.append("UNKNOWN\",\"isScreenUp\":false");
        }
    }

    private static void addRoom(StringBuilder sb) {
        sb.append("\"room\":{");
        try {
            Object room = ReflectionUtil.invokeStatic(
                "com.megacrit.cardcrawl.dungeons.AbstractDungeon", "getCurrRoom");
            if (room != null) {
                Object phase = ReflectionUtil.getField(room, "phase");
                sb.append("\"type\":\"");
                sb.append(room.getClass().getSimpleName());
                sb.append("\",\"phase\":\"");
                sb.append(String.valueOf(phase));
                sb.append("\"");
            } else {
                sb.append("\"type\":\"null\"");
            }
        } catch (Throwable t) {
            sb.append("\"type\":\"error\"");
        }
        sb.append("}");
    }

    private static void addCombat(StringBuilder sb) {
        sb.append("\"combat\":{");
        try {
            Object player = ReflectionUtil.getStaticField(
                "com.megacrit.cardcrawl.dungeons.AbstractDungeon", "player");
            if (player == null) {
                sb.append("\"active\":false}");
                return;
            }
            sb.append("\"active\":true");

            int hp = getIntField(player, "currentHealth");
            int maxHp = getIntField(player, "maxHealth");
            int block = getIntField(player, "currentBlock");
            sb.append(",\"player\":{\"hp\":").append(hp)
                .append(",\"maxHp\":").append(maxHp)
                .append(",\"block\":").append(block).append("}");

            int energy = 0;
            try {
                Object ep = ReflectionUtil.getStaticField(
                    "com.megacrit.cardcrawl.ui.panels.EnergyPanel", "totalCount");
                if (ep instanceof Integer) energy = (Integer) ep;
            } catch (Throwable t) {}
            sb.append(",\"energy\":").append(energy);

            // hand
            sb.append(",\"hand\":[");
            try {
                Object hand = ReflectionUtil.getField(player, "hand");
                Object group = ReflectionUtil.getField(hand, "group");
                java.util.List<?> cards = (java.util.List<?>) group;
                boolean first = true;
                for (int i = 0; i < cards.size(); i++) {
                    if (!first) sb.append(",");
                    first = false;
                    Object card = cards.get(i);
                    String cardId = String.valueOf(ReflectionUtil.getField(card, "cardID"));
                    int cost = getIntField(card, "costForTurn");
                    sb.append("{\"index\":").append(i)
                        .append(",\"id\":\"").append(escape(cardId)).append("\"")
                        .append(",\"cost\":").append(cost)
                        .append("}");
                }
            } catch (Throwable t) {}
            sb.append("]");

            // monsters
            sb.append(",\"monsters\":[");
            try {
                Object room = ReflectionUtil.invokeStatic(
                    "com.megacrit.cardcrawl.dungeons.AbstractDungeon", "getCurrRoom");
                Object monsters = ReflectionUtil.getField(room, "monsters");
                java.util.List<?> mlist = (java.util.List<?>) ReflectionUtil.getField(monsters, "monsters");
                if (mlist != null) {
                    boolean first = true;
                    for (int i = 0; i < mlist.size(); i++) {
                        Object m = mlist.get(i);
                        if (!first) sb.append(",");
                        first = false;
                        boolean isDead = getBoolField(m, "isDead");
                        boolean isDying = getBoolField(m, "isDying");
                        if (isDead || isDying) {
                            sb.append("{\"index\":").append(i).append(",\"dead\":true}");
                            continue;
                        }
                        String mId = String.valueOf(ReflectionUtil.getField(m, "id"));
                        int mHp = getIntField(m, "currentHealth");
                        int mMaxHp = getIntField(m, "maxHealth");
                        int mBlock = getIntField(m, "currentBlock");
                        sb.append("{\"index\":").append(i)
                            .append(",\"id\":\"").append(escape(mId)).append("\"")
                            .append(",\"hp\":").append(mHp)
                            .append(",\"maxHp\":").append(mMaxHp)
                            .append(",\"block\":").append(mBlock).append("}");
                    }
                }
            } catch (Throwable t) {}
            sb.append("]");

        } catch (Throwable t) {
            sb.append("\"active\":false");
        }
        sb.append("}");
    }

    private static void addMap(StringBuilder sb) {
        sb.append("\"map\":{\"available\":");
        try {
            Object current = ReflectionUtil.invokeStatic(
                "com.megacrit.cardcrawl.dungeons.AbstractDungeon", "getCurrMapNode");
            if (current == null) {
                sb.append("false}");
                return;
            }
            int cx = getIntField(current, "x");
            int cy = getIntField(current, "y");
            sb.append("true,\"current\":{\"x\":").append(cx)
                .append(",\"y\":").append(cy).append("}");

            sb.append(",\"reachable\":[");
            Object map = ReflectionUtil.getStaticField(
                "com.megacrit.cardcrawl.dungeons.AbstractDungeon", "map");
            java.util.List<?> rows = (java.util.List<?>) map;
            int nextY = cy + 1;
            if (rows != null && nextY < rows.size()) {
                java.util.List<?> row = (java.util.List<?>) rows.get(nextY);
                if (row != null) {
                    boolean first = true;
                    for (Object node : row) {
                        Boolean hasEdges = (Boolean) node.getClass()
                            .getMethod("hasEdges").invoke(node);
                        if (hasEdges == null || !hasEdges) continue;
                        if (cy > 0) {
                            Class<?> roomNodeCls = ReflectionUtil.forName(
                                "com.megacrit.cardcrawl.map.MapRoomNode");
                            if (roomNodeCls == null) continue;
                            Boolean connected = (Boolean) current.getClass()
                                .getMethod("isConnectedTo", roomNodeCls)
                                .invoke(current, node);
                            if (connected == null || !connected) continue;
                        }
                        if (!first) sb.append(",");
                        first = false;
                        int nx = getIntField(node, "x");
                        String roomType = "UNKNOWN";
                        try {
                            Object room = ReflectionUtil.getField(node, "room");
                            if (room != null) roomType = room.getClass().getSimpleName();
                        } catch (Throwable ignored) {}
                        sb.append("{\"x\":").append(nx)
                            .append(",\"y\":").append(nextY)
                            .append(",\"type\":\"").append(escape(roomType)).append("\"}");
                    }
                }
            }
            sb.append("]");
        } catch (Throwable t) {
            sb.append("false");
        }
        sb.append("}");
    }

    private static int getIntField(Object obj, String name) {
        Object v = ReflectionUtil.getField(obj, name);
        return v instanceof Integer ? (Integer) v : 0;
    }

    private static boolean getBoolField(Object obj, String name) {
        Object v = ReflectionUtil.getField(obj, name);
        return v instanceof Boolean ? (Boolean) v : false;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
