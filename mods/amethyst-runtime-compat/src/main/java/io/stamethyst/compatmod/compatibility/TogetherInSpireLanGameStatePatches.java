package io.stamethyst.compatmod.compatibility;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.core.CardCrawlGame;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

public final class TogetherInSpireLanGameStatePatches {
    private static final String MOD_ID = "spireTogether";
    private static final String SPIRE_TOGETHER_MOD_CLASS = "spireTogether.SpireTogetherMod";
    private static final String GAME_STATE_REQUEST_PROPERTY =
        "amethyst.in_game_lan_game_state_request";
    private static final long STATE_POLL_INTERVAL_MS = 500L;
    private static final long GAME_STATE_HEARTBEAT_INTERVAL_MS = 25_000L;

    private static volatile Method isConnectedToGameMethod;
    private static volatile long nextStatePollAtMs;
    private static volatile long lastGameStateReportAtMs;
    private static volatile boolean previouslyConnected;

    private TogetherInSpireLanGameStatePatches() {
    }

    @SpirePatch2(
        clz = CardCrawlGame.class,
        method = "update",
        requiredModId = MOD_ID,
        optional = true
    )
    public static class CardCrawlGameUpdatePatch {
        @SpirePostfixPatch
        public static void Postfix() {
            reportGameStateIfNeeded();
        }
    }

    private static void reportGameStateIfNeeded() {
        long nowMs = System.currentTimeMillis();
        if (nowMs < nextStatePollAtMs) {
            return;
        }
        nextStatePollAtMs = nowMs + STATE_POLL_INTERVAL_MS;

        boolean connected = isConnectedToTogetherInSpireGame();
        if (connected) {
            if (!previouslyConnected || nowMs - lastGameStateReportAtMs >= GAME_STATE_HEARTBEAT_INTERVAL_MS) {
                writeGameStateRequest("game", nowMs);
                lastGameStateReportAtMs = nowMs;
            }
        } else if (previouslyConnected) {
            writeGameStateRequest("online", nowMs);
            lastGameStateReportAtMs = 0L;
        }
        previouslyConnected = connected;
    }

    private static boolean isConnectedToTogetherInSpireGame() {
        try {
            Method method = isConnectedToGameMethod;
            if (method == null) {
                method = Class.forName(
                    SPIRE_TOGETHER_MOD_CLASS,
                    false,
                    TogetherInSpireLanGameStatePatches.class.getClassLoader()
                ).getMethod("isConnectedToGame");
                isConnectedToGameMethod = method;
            }
            return Boolean.TRUE.equals(method.invoke(null));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void writeGameStateRequest(String state, long nowMs) {
        String path = System.getProperty(GAME_STATE_REQUEST_PROPERTY, "").trim();
        if (path.isEmpty()) {
            return;
        }
        File requestFile = new File(path);
        File parent = requestFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return;
        }
        File temporaryFile = new File(requestFile.getParentFile(), "." + requestFile.getName() + ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(temporaryFile, false)) {
                output.write((state + "\n" + nowMs + "\n").getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            if (!temporaryFile.renameTo(requestFile)) {
                try (FileOutputStream output = new FileOutputStream(requestFile, false)) {
                    output.write((state + "\n" + nowMs + "\n").getBytes(StandardCharsets.UTF_8));
                    output.getFD().sync();
                }
            }
        } catch (Exception ignored) {
        } finally {
            temporaryFile.delete();
        }
    }
}
