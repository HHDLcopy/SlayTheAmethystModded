package com.badlogic.gdx.backends.lwjgl;

import com.badlogic.gdx.utils.GdxNativesLoader;
import com.badlogic.gdx.utils.GdxRuntimeException;

import java.io.File;

/**
 * Patched for SlayTheAmethyst:
 * - Skip extracting desktop natives from JAR.
 * - Use POJAV_NATIVEDIR and prepackaged arm64 libs.
 */
public final class LwjglNativesLoader {
    public static boolean load = true;

    private LwjglNativesLoader() {
    }

    public static void load() {
        GdxNativesLoader.load();
        if (GdxNativesLoader.disableNativesLoading) return;
        if (!load) return;

        String nativeDir = System.getenv("POJAV_NATIVEDIR");
        if (nativeDir == null || nativeDir.length() == 0) {
            nativeDir = System.getProperty("java.library.path");
        }
        if (nativeDir == null || nativeDir.length() == 0) {
            throw new GdxRuntimeException("Unable to resolve native directory for LWJGL");
        }

        // Prefer external resource directories over the APK lib dir when both contain a
        // library. Full builds bundle the same natives into the APK, and those can drift
        // out of sync with the downloaded resource pack; resolving the external copy first
        // keeps every library on a single version. LWJGL searches these entries in order
        // and falls through to the APK dir for natives that are never externalized
        // (liblwjgl.so, libopenal.so, ...).
        String externalNativeDirs = System.getProperty("amethyst.gdx.native_dir");
        if (externalNativeDirs == null || externalNativeDirs.length() == 0) {
            externalNativeDirs = System.getenv("AMETHYST_GDX_NATIVE_DIR");
        }
        String effectiveLibraryPath;
        if (externalNativeDirs != null && externalNativeDirs.length() > 0) {
            effectiveLibraryPath = externalNativeDirs + File.pathSeparator + nativeDir;
        } else {
            effectiveLibraryPath = nativeDir;
        }

        // LWJGL uses this value when resolving liblwjgl/libopenal.
        System.setProperty("org.lwjgl.librarypath", effectiveLibraryPath);
        System.out.println("[gdx-patch] LwjglNativesLoader.load nativeDir=" + nativeDir
                + " externalNativeDirs=[" + externalNativeDirs + "]"
                + " org.lwjgl.librarypath=" + System.getProperty("org.lwjgl.librarypath"));

        load = false;
    }

    static {
        System.setProperty("org.lwjgl.input.Mouse.allowNegativeMouseCoords", "true");
        try {
            Class.forName("javax.jnlp.ServiceManager")
                    .getDeclaredMethod("lookup", String.class)
                    .invoke(null, "javax.jnlp.PersistenceService");
            load = false;
        } catch (Throwable ignored) {
            load = true;
        }
    }
}
