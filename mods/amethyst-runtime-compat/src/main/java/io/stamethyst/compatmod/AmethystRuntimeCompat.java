package io.stamethyst.compatmod;

import com.evacipated.cardcrawl.modthespire.lib.SpireInitializer;

import io.stamethyst.compatmod.autoplay.AutoplayConfig;
import io.stamethyst.compatmod.autoplay.AutoplayLog;

@SpireInitializer
public class AmethystRuntimeCompat {
    public static void initialize() {
        CompatRuntimeState.logStartupConfiguration();
        RuntimeMemoryDiagnostics.logStartupConfiguration();
        if (AutoplayConfig.isEnabled()) {
            AutoplayLog.info(
                "autoplay configured tickIntervalMs=" + AutoplayConfig.getTickIntervalMs()
                    + " debugLog=" + AutoplayConfig.isDebugLogEnabled()
            );
        }
    }
}
