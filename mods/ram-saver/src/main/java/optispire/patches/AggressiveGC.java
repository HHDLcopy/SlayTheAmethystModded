package optispire.patches;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.helpers.FontHelper;
import optispire.RamSaverDiag;

public class AggressiveGC {
    // Rate limiting: allow at most 1 System.gc() per THROTTLE_INTERVAL_MS
    // Default 45 seconds - balances memory reclamation with frame stability
    private static final long THROTTLE_INTERVAL_MS = readLong("ramsaver.gc.throttle_seconds", 45L, 10L, 300L) * 1000L;
    private static volatile long lastGcTimeMs = 0L;
    private static volatile int suppressedGcCount = 0;
    
    private static long readLong(String key, long defaultValue, long min, long max) {
        try {
            String value = System.getProperty(key);
            if (value != null && !value.isEmpty()) {
                long parsed = Long.parseLong(value.trim());
                if (parsed >= min && parsed <= max) {
                    return parsed;
                }
            }
        } catch (Exception ignored) {
        }
        return defaultValue;
    }
    
    @SpirePatch(
            cls = "basemod.BaseMod",
            method = "publishPostInitialize",
            optional = true
    )
    @SpirePatch(
            cls = "basemod.BaseMod",
            method = "publishEditCards",
            optional = true
    )
    @SpirePatch(
            cls = "basemod.BaseMod",
            method = "publishEditRelics",
            optional = true
    )
    @SpirePatch(
            cls = "basemod.BaseMod",
            method = "publishEditCharacters",
            optional = true
    )
    @SpirePatch(
            cls = "basemod.BaseMod",
            method = "publishEditStrings",
            optional = true
    )
    @SpirePatch(
            cls = "basemod.BaseMod",
            method = "publishEditKeywords",
            optional = true
    )
    @SpirePatch(
            clz = FontHelper.class,
            method = "initialize"
    )
    public static class Initialization {
        @SpirePostfixPatch
        public static void after() {
            boolean diag = RamSaverDiag.enabled();
            long nowMs = System.currentTimeMillis();
            long elapsedSinceLastGc = nowMs - lastGcTimeMs;
            
            // Throttle: skip if we did a GC too recently
            if (lastGcTimeMs > 0 && elapsedSinceLastGc < THROTTLE_INTERVAL_MS) {
                suppressedGcCount++;
                if (diag) {
                    RamSaverDiag.logRepeat(
                        "aggressive_gc_suppressed", 
                        "System.gc",
                        "elapsedMs=" + elapsedSinceLastGc 
                            + " throttleMs=" + THROTTLE_INTERVAL_MS
                            + " suppressedCount=" + suppressedGcCount
                    );
                }
                return;
            }
            
            long started = diag ? System.nanoTime() : 0L;
            if (diag) {
                RamSaverDiag.logStackRepeat(
                    "aggressive_gc_request", 
                    "System.gc", 
                    "elapsedMs=" + elapsedSinceLastGc 
                        + " suppressedCount=" + suppressedGcCount
                );
            }
            
            lastGcTimeMs = nowMs;
            suppressedGcCount = 0;
            System.gc();
            
            if (diag) {
                RamSaverDiag.logDuration("aggressive_gc", "System.gc", started, "after", true);
            }
        }
    }
}
