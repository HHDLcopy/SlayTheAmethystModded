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
 *  launcher can upload it via CMsgClientRichPresenceUpload (EMsg 761). */
public final class RichPresenceBridge {
    private static final String PRESENCE_PATH_PROP = "amethyst.richpresence.path";
    private static boolean initialized;
    private static String lastWrittenPayload = "";

    private RichPresenceBridge() {
    }

    public static void initialize() {
        initialized = true;
        System.out.println(
            "[amethyst-presence] bridge initialized pathConfigured="
                + Boolean.toString(presenceFile() != null)
        );
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
            Map<String, String> kv = new LinkedHashMap<>();
            kv.put("steam_display", "#StatusInGame");
            kv.put("character", character);
            kv.put("floor", String.valueOf(floorNum));
            // Dungeon id (e.g. "Exordium", "TheCity") — read via reflection to avoid
            // a compile-time dependency on the exact field name.
            String dungeonId = readDungeonId();
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
