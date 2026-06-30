package io.stamethyst.bridge;

import com.evacipated.cardcrawl.modthespire.MTSClassPool;
import com.evacipated.cardcrawl.modthespire.PackageJar;
import com.evacipated.cardcrawl.modthespire.Loader;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MtsPatchCacheStoreTest {
    private static final String PROP_ENABLED = "amethyst.mts.patch_cache.enabled";
    private static final String PROP_JAR = "amethyst.mts.patch_cache.jar";
    private static final String PROP_MARKER = "amethyst.mts.patch_cache.marker";
    private static final String PROP_PACKAGE_DIR = "amethyst.mts.patch_cache.package_dir";
    private static final String PROP_EXPECTED = "amethyst.mts.patch_cache.expected";

    @Test
    public void store_writesMarkerWhenCacheJarAndPackageJarsExist() throws Exception {
        File root = Files.createTempDirectory("mts-patch-cache-store-").toFile();
        try {
            setCacheProperties(root);
            resetStubTracking();
            PackageJar.writePackageJarFiles = true;

            MtsPatchCacheStore.store(new MTSClassPool());

            assertTrue(new File(root, "desktop-1.0-modded.jar").length() >= 1024L * 1024L);
            assertTrue(new File(root, ".mts_patch_cache").isFile());
            assertTrue(new File(root, "package/Example's Mod-modded.jar").isFile());
            assertTrue(new File(root, "package/Exampleu0027s Mod-modded.jar").isFile());
            assertEquals(1, MTSClassPool.getModifiedClassesCalls);
            assertTrue(MTSClassPool.getOutJarClassesCalls >= 1);
            assertFalse(PackageJar.observedOutJarWasNull);
            assertEquals(1, PackageJar.observedOutJarSize);
            assertFalse(PackageJar.observedPackageFlag);
            assertFalse(Loader.PACKAGE);
            assertFalse(Loader.OUT_JAR);
        } finally {
            clearCacheProperties();
            resetStubTracking();
            PackageJar.writePackageJarFiles = true;
            deleteRecursively(root);
        }
    }

    @Test
    public void store_rejectsCacheWhenPackageJarsAreMissing() throws Exception {
        File root = Files.createTempDirectory("mts-patch-cache-store-missing-package-").toFile();
        try {
            setCacheProperties(root);
            resetStubTracking();
            PackageJar.writePackageJarFiles = false;

            MtsPatchCacheStore.store(new MTSClassPool());

            assertFalse(new File(root, "desktop-1.0-modded.jar").exists());
            assertFalse(new File(root, ".mts_patch_cache").exists());
        } finally {
            clearCacheProperties();
            resetStubTracking();
            PackageJar.writePackageJarFiles = true;
            deleteRecursively(root);
        }
    }

    @Test
    public void store_reusesOutJarCapturedDuringCompileBeforeDetachedClassesDisappear() throws Exception {
        File root = Files.createTempDirectory("mts-patch-cache-store-compile-capture-").toFile();
        try {
            setCacheProperties(root);
            resetStubTracking();
            PackageJar.writePackageJarFiles = true;
            MTSClassPool.detachAfterCompileSnapshot = true;
            MTSClassPool classPool = new MTSClassPool();

            MtsPatchCacheStore.beginCompileCapture();
            try {
                assertEquals(1, classPool.getModifiedClasses().size());
            } finally {
                MtsPatchCacheStore.finishCompileCapture();
            }

            MtsPatchCacheStore.store(classPool);

            assertTrue(new File(root, "desktop-1.0-modded.jar").isFile());
            assertTrue(new File(root, ".mts_patch_cache").isFile());
            assertEquals(1, MTSClassPool.getModifiedClassesCalls);
            assertTrue(MTSClassPool.getOutJarClassesCalls >= 2);
            assertFalse(PackageJar.observedOutJarWasNull);
            assertEquals(1, PackageJar.observedOutJarSize);
            assertFalse(Loader.PACKAGE);
            assertFalse(Loader.OUT_JAR);
        } finally {
            clearCacheProperties();
            resetStubTracking();
            PackageJar.writePackageJarFiles = true;
            deleteRecursively(root);
        }
    }

    private static void setCacheProperties(File root) {
        System.setProperty(PROP_ENABLED, "true");
        System.setProperty(PROP_JAR, new File(root, "desktop-1.0-modded.jar").getAbsolutePath());
        System.setProperty(PROP_MARKER, new File(root, ".mts_patch_cache").getAbsolutePath());
        System.setProperty(PROP_PACKAGE_DIR, new File(root, "package").getAbsolutePath());
        System.setProperty(PROP_EXPECTED, "expected");
    }

    private static void resetStubTracking() {
        Loader.PACKAGE = false;
        Loader.OUT_JAR = false;
        MTSClassPool.resetTracking();
        PackageJar.resetTracking();
    }

    private static void clearCacheProperties() {
        System.clearProperty(PROP_ENABLED);
        System.clearProperty(PROP_JAR);
        System.clearProperty(PROP_MARKER);
        System.clearProperty(PROP_PACKAGE_DIR);
        System.clearProperty(PROP_EXPECTED);
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
