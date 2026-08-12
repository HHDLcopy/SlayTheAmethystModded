package io.stamethyst.compatmod.presence;

import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** JVM-only IPC bridge: atomically writes current rich-presence key-value state so the
 *  launcher can upload it via CMsgClientRichPresenceUpload (EMsg 7501).
 *
 *  <p>Key contract (matches BaseMod.setRichPresence and Steam's documented rules):
 *  the human-readable text goes into {@code status}, while {@code steam_display} must be
 *  a localization token registered for the app. Steam states that if {@code steam_display}
 *  is not a valid localization tag, rich presence is not displayed at all — so raw text
 *  there silently shows nothing. AppID 646570 ships {@code #Status}, which resolves to
 *  {@code %status%}, so {@code #Status} is the only correct {@code steam_display} value. */
public final class RichPresenceBridge {
    private static final String PRESENCE_PATH_PROP = "amethyst.richpresence.path";
    /** Localization token registered by Slay the Spire; renders the `status` value. */
    private static final String STEAM_DISPLAY_TOKEN = "#Status";
    private static boolean initialized;
    private static String lastWrittenPayload = "";

    private RichPresenceBridge() {
    }

    public static void initialize() {
        initialized = true;
        publishMainMenuState();
        System.out.println(
            "[amethyst-presence] bridge initialized pathConfigured="
                + Boolean.toString(presenceFile() != null)
        );
    }

    /** Makes the launcher aware of an active game before a dungeon has been created. */
    private static void publishMainMenuState() {
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("status", "主菜单");
        kv.put("steam_display", STEAM_DISPLAY_TOKEN);
        String payload = serializeKv(kv);
        boolean written = writePresence(payload);
        if (written) {
            lastWrittenPayload = payload;
        }
        System.out.println("[amethyst-presence] main_menu written=" + written);
    }

    /**
     * Called on dungeon state transitions (floor change, run start).
     * Reads current {@link AbstractDungeon} and {@link CardCrawlGame#player} statics,
     * serialises to key=value lines, and atomically overwrites the IPC file when the
     * state has changed.
     */
    public static void updateDungeonState() {
        if (!initialized) return;
        Map<String, String> kv = buildKvPairs();
        if (kv == null) return;
        String payload = serializeKv(kv);
        if (payload.equals(lastWrittenPayload)) return;
        boolean written = writePresence(payload);
        if (written) {
            lastWrittenPayload = payload;
        }
        System.out.println(
            "[amethyst-presence] state_updated written=" + written
                + " floor=" + kv.get("floor")
                + " character=" + kv.get("character")
        );
    }

    // -----------------------------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------------------------

    private static Map<String, String> buildKvPairs() {
        try {
            AbstractPlayer player = AbstractDungeon.player;
            if (player == null) return null;
            int floorNum = AbstractDungeon.floorNum;
            String character = player.chosenClass != null
                ? player.chosenClass.name().toLowerCase(java.util.Locale.ROOT)
                : "unknown";
            // Use the localized character name for the human-readable status string.
            // Falls back to the enum name if the method is unavailable.
            String displayName;
            try {
                displayName = player.getLocalizedCharacterName();
                if (displayName == null || displayName.isEmpty()) displayName = character;
            } catch (Throwable ignored) {
                displayName = character;
            }
            // Dungeon id (e.g. "Exordium", "TheCity") — read via reflection to avoid
            // a compile-time dependency on the exact field name.
            String dungeonId = readDungeonId();
            // The visible text must live in `status`; `steam_display` only names the
            // localization token that renders it. See the class javadoc for why raw text
            // in `steam_display` never displays.
            String displayText = dungeonId != null && !dungeonId.isEmpty()
                ? displayName + " · 第" + floorNum + "层 · " + dungeonId
                : displayName + " · 第" + floorNum + "层";
            Map<String, String> kv = new LinkedHashMap<>();
            kv.put("status", displayText);
            kv.put("steam_display", STEAM_DISPLAY_TOKEN);
            kv.put("character", character);
            kv.put("floor", String.valueOf(floorNum));
            if (dungeonId != null && !dungeonId.isEmpty()) {
                kv.put("act", dungeonId);
            }
            return kv;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readDungeonId() {
        try {
            // AbstractDungeon.id is an instance field; the current dungeon is a static
            // reference stored on AbstractDungeon (field name varies by build).
            java.lang.reflect.Field dungeonField = null;
            for (java.lang.reflect.Field f : AbstractDungeon.class.getDeclaredFields()) {
                if ("id".equals(f.getName()) && f.getType() == String.class
                        && java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    dungeonField = f;
                    break;
                }
            }
            if (dungeonField == null) return null;
            dungeonField.setAccessible(true);
            Object value = dungeonField.get(null);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String serializeKv(Map<String, String> kv) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : kv.entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(entry.getKey()).append('=').append(escapeValue(entry.getValue()));
        }
        return sb.toString();
    }

    /** Escapes newlines and equals signs in values to keep the format unambiguous. */
    private static String escapeValue(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("=", "\\=");
    }

    private static boolean writePresence(String payload) {
        File target = presenceFile();
        if (target == null) return false;
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();
        File temporary = new File(target.getPath() + ".tmp");
        try {
            Writer writer = new OutputStreamWriter(
                new FileOutputStream(temporary), StandardCharsets.UTF_8.name());
            try {
                writer.write(payload);
                writer.write('\n');
            } finally {
                writer.close();
            }
            try {
                Files.move(temporary.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFailure) {
                Files.move(temporary.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception error) {
            temporary.delete();
            System.out.println(
                "[amethyst-presence] write failed error=" + error.getClass().getSimpleName()
            );
            return false;
        }
    }

    private static File presenceFile() {
        String path = System.getProperty(PRESENCE_PATH_PROP, "").trim();
        return path.isEmpty() ? null : new File(path);
    }
}
