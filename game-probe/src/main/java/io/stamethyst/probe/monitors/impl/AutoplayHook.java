package io.stamethyst.probe.monitors.impl;

import io.stamethyst.probe.util.ReflectionUtil;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Game-logic primitives called by {@link PlayMonitor} on the render
 * thread.  All game-class access routes through {@link ReflectionUtil}
 * which uses the captured MTS ClassLoader.
 */
public final class AutoplayHook {

    private static final Random RNG = new Random();

    private AutoplayHook() {}

    // ── Combat ────────────────────────────────────────────────────

    public static void playRandomCard() {
        try {
            Object player = ReflectionUtil.getStaticField(
                "com.megacrit.cardcrawl.dungeons.AbstractDungeon", "player");
            if (player == null) return;
            Object hand = ReflectionUtil.getField(player, "hand");
            if (hand == null) return;
            List<?> handGroup = (List<?>) ReflectionUtil.getField(hand, "group");
            if (handGroup == null || handGroup.isEmpty()) return;

            Object room = ReflectionUtil.invokeStatic(
                "com.megacrit.cardcrawl.dungeons.AbstractDungeon", "getCurrRoom");
            Object monsters = ReflectionUtil.getField(room, "monsters");
            if (monsters == null) return;
            List<?> monsterList = (List<?>) ReflectionUtil.getField(monsters, "monsters");

            Class<?> abstractPlayer = g("com.megacrit.cardcrawl.characters.AbstractPlayer");
            Class<?> abstractMonster = g("com.megacrit.cardcrawl.monsters.AbstractMonster");
            if (abstractPlayer == null || abstractMonster == null) return;
            Class<?> abstractCard = g("com.megacrit.cardcrawl.cards.AbstractCard");
            if (abstractCard == null) return;

            List<Object> playable = new ArrayList<Object>();
            for (Object card : handGroup) {
                Object target = pickRandomAlive(monsterList);
                try {
                    Boolean canUse = (Boolean) card.getClass()
                        .getMethod("canUse", abstractPlayer, abstractMonster)
                        .invoke(card, player, target);
                    if (Boolean.TRUE.equals(canUse)) playable.add(card);
                } catch (Throwable ignored) {}
            }
            if (playable.isEmpty()) return;

            Object card = playable.get(RNG.nextInt(playable.size()));
            Object target = pickRandomAlive(monsterList);
            int energy = getEnergy();
            player.getClass().getMethod("useCard", abstractCard, abstractMonster, int.class)
                .invoke(player, card, target, energy);
        } catch (Throwable ignored) {}
    }

    public static void playCardTargeted(int cardIndex, int monsterIndex) {
        try {
            Object player = ReflectionUtil.getStaticField(
                "com.megacrit.cardcrawl.dungeons.AbstractDungeon", "player");
            if (player == null) return;
            Object hand = ReflectionUtil.getField(player, "hand");
            if (hand == null) return;
            List<?> handGroup = (List<?>) ReflectionUtil.getField(hand, "group");
            if (handGroup == null || handGroup.isEmpty()) return;
            Object room = ReflectionUtil.invokeStatic(
                "com.megacrit.cardcrawl.dungeons.AbstractDungeon", "getCurrRoom");
            Object monsters = ReflectionUtil.getField(room, "monsters");
            if (monsters == null) return;
            List<?> monsterList = (List<?>) ReflectionUtil.getField(monsters, "monsters");
            if (monsterList == null || monsterList.isEmpty()) return;
            if (cardIndex < 0 || cardIndex >= handGroup.size()) { playRandomCard(); return; }
            Object card = handGroup.get(cardIndex);
            Object target = (monsterIndex >= 0 && monsterIndex < monsterList.size())
                ? monsterList.get(monsterIndex) : pickRandomAlive(monsterList);
            if (target == null) return;
            Class<?> ap = g("com.megacrit.cardcrawl.characters.AbstractPlayer");
            Class<?> am = g("com.megacrit.cardcrawl.monsters.AbstractMonster");
            Class<?> ac = g("com.megacrit.cardcrawl.cards.AbstractCard");
            if (ap == null || am == null || ac == null) return;
            boolean canUse = (Boolean) card.getClass()
                .getMethod("canUse", ap, am).invoke(card, player, target);
            if (!canUse) return;
            int energy = getEnergy();
            player.getClass().getMethod("useCard", ac, am, int.class)
                .invoke(player, card, target, energy);
        } catch (Throwable ignored) {}
    }

    public static void endTurn() {
        try {
            Object am = ReflectionUtil.getStaticField(
                "com.megacrit.cardcrawl.dungeons.AbstractDungeon", "actionManager");
            if (am != null) {
                am.getClass().getMethod("callEndTurnEarlySequence").invoke(am);
            }
        } catch (Throwable ignored) {}
    }

    public static void pressProceed() {
        try {
            Object overlay = ReflectionUtil.getStaticField(
                "com.megacrit.cardcrawl.dungeons.AbstractDungeon", "overlayMenu");
            if (overlay == null) return;
            Object btn = ReflectionUtil.getField(overlay, "proceedButton");
            if (btn == null) return;
            Boolean hidden = (Boolean) ReflectionUtil.getField(btn, "isHidden");
            if (hidden == null || !hidden) {
                Object hb = ReflectionUtil.getField(btn, "hb");
                if (hb != null) {
                    hb.getClass().getField("clicked").set(hb, true);
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void skipRoom() {
        try {
            Object room = ReflectionUtil.invokeStatic(
                "com.megacrit.cardcrawl.dungeons.AbstractDungeon", "getCurrRoom");
            if (room == null) return;
            Field phaseField = room.getClass().getField("phase");
            Object phase = phaseField.get(room);
            Class<?> roomPhase = g("com.megacrit.cardcrawl.rooms.AbstractRoom$RoomPhase");
            if (roomPhase == null) return;
            Object completeEnum = roomPhase.getField("COMPLETE").get(null);
            if (phase != completeEnum) {
                phaseField.set(room, completeEnum);
                g("com.megacrit.cardcrawl.rooms.AbstractRoom")
                    .getField("waitTimer").set(null, 0.0F);
                pressProceed();
            }
        } catch (Throwable ignored) {}
    }

    // ── Helpers ───────────────────────────────────────────────────

    private static int getEnergy() {
        Object ep = ReflectionUtil.getStaticField(
            "com.megacrit.cardcrawl.ui.panels.EnergyPanel", "totalCount");
        return ep instanceof Integer ? (Integer) ep : 0;
    }

    private static Object pickRandomAlive(List<?> monsters) {
        List<Object> alive = new ArrayList<Object>();
        for (Object m : monsters) {
            try {
                Boolean dying = (Boolean) ReflectionUtil.getField(m, "isDying");
                Boolean dead = (Boolean) ReflectionUtil.getField(m, "isDead");
                Boolean escaped = (Boolean) ReflectionUtil.getField(m, "escaped");
                if (!Boolean.TRUE.equals(dying) && !Boolean.TRUE.equals(dead)
                    && !Boolean.TRUE.equals(escaped)) {
                    alive.add(m);
                }
            } catch (Throwable ignored) {}
        }
        if (alive.isEmpty() && !monsters.isEmpty()) {
            return monsters.get(RNG.nextInt(monsters.size()));
        }
        return alive.isEmpty() ? null : alive.get(RNG.nextInt(alive.size()));
    }

    /** Shortcut: ReflectionUtil.forName, returns null on failure. */
    private static Class<?> g(String fqcn) {
        return ReflectionUtil.forName(fqcn);
    }
}
