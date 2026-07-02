package io.stamethyst.bridge;

import com.evacipated.cardcrawl.modthespire.ByteArrayMapClassPath;
import com.evacipated.cardcrawl.modthespire.MTSClassPool;
import com.evacipated.cardcrawl.modthespire.ModInfo;
import com.evacipated.cardcrawl.modthespire.PackageJar;
import com.evacipated.cardcrawl.modthespire.Loader;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

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
            String previousUserDir = System.getProperty("user.dir");

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
            assertEquals(root.getAbsolutePath(), PackageJar.observedUserDir);
            assertEquals(previousUserDir, System.getProperty("user.dir"));
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
    public void resolvePackageDirPath_usesCachePackageDirOnlyWhenCacheIsEnabled() throws Exception {
        File root = Files.createTempDirectory("mts-patch-cache-store-package-dir-").toFile();
        try {
            clearCacheProperties();
            System.setProperty(PROP_PACKAGE_DIR, new File(root, "package").getAbsolutePath());
            assertEquals("package", MtsPatchCacheStore.resolvePackageDirPath());

            System.setProperty(PROP_ENABLED, "true");
            assertEquals(new File(root, "package").getAbsolutePath(), MtsPatchCacheStore.resolvePackageDirPath());
        } finally {
            clearCacheProperties();
            deleteRecursively(root);
        }
    }

    @Test
    public void packageJarFastPath_writesCacheJarAndPackageJarsFromEntries() throws Exception {
        File root = Files.createTempDirectory("mts-patch-cache-fast-package-").toFile();
        try {
            setCacheProperties(root);
            resetStubTracking();
            File baseJar = new File(root, "base.jar");
            File modJar = new File(root, "Example Mod.jar");
            writeJar(baseJar, "base/Base.class", "base");
            writeJar(modJar, "example/ExampleMod.class", "mod");
            Loader.STS_JAR = baseJar.getAbsolutePath();
            Loader.MODINFOS = new ModInfo[] {
                    new ModInfo("example", modJar.toURI().toURL())
            };

            PackageJar.Entries entries = new PackageJar.Entries();
            entries.add(new PackageJar.Entry(
                    "com/evacipated/cardcrawl/modthespire/PackageJar$PrepackagedLauncher.class",
                    "launcher".getBytes("UTF-8"),
                    null
            ));
            entries.add(new PackageJar.Entry("base/Base.class", PackageJar.Type.BASEGAME));
            entries.add(new PackageJar.Entry(
                    "base/Patched.class",
                    "patched".getBytes("UTF-8"),
                    null
            ));
            entries.add(new PackageJar.Entry("example/ExampleMod.class", "example"));
            entries.add(new PackageJar.Entry(
                    "example/Generated.class",
                    "generated".getBytes("UTF-8"),
                    modJar.toURI().toURL()
            ));

            JarOutputStream openOutput = new JarOutputStream(
                    new FileOutputStream(new File(root, "desktop-1.0-modded.jar"))
            );

            assertTrue(MtsPatchCacheStore.packageJarFastPath(
                    new MTSClassPool(),
                    entries,
                    openOutput,
                    new File(root, "desktop-1.0-modded.jar").getAbsolutePath()
            ));

            File cachedJar = new File(root, "desktop-1.0-modded.jar");
            File packageJar = new File(root, "package/Example Mod-modded.jar");
            assertTrue(cachedJar.isFile());
            assertTrue(packageJar.isFile());
            assertTrue(hasJarEntry(cachedJar, "META-INF/MANIFEST.MF"));
            assertTrue(java.util.Arrays.equals("base".getBytes("UTF-8"), readJarEntry(cachedJar, "base/Base.class")));
            assertTrue(java.util.Arrays.equals("patched".getBytes("UTF-8"), readJarEntry(cachedJar, "base/Patched.class")));
            assertTrue(java.util.Arrays.equals("mod".getBytes("UTF-8"), readJarEntry(packageJar, "example/ExampleMod.class")));
            assertTrue(java.util.Arrays.equals("generated".getBytes("UTF-8"), readJarEntry(packageJar, "example/Generated.class")));
        } finally {
            clearCacheProperties();
            resetStubTracking();
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
    public void store_migratesPackageJarsGeneratedInLaunchUserDir() throws Exception {
        File root = Files.createTempDirectory("mts-patch-cache-store-migrate-package-").toFile();
        String previousUserDir = System.getProperty("user.dir");
        try {
            File cacheRoot = new File(root, "cache");
            File launchRoot = new File(root, "launch");
            assertTrue(cacheRoot.mkdirs());
            assertTrue(launchRoot.mkdirs());
            setCacheProperties(cacheRoot);
            resetStubTracking();
            PackageJar.writePackageJarFiles = true;
            PackageJar.forcedPackageDir = new File(launchRoot, "package").getAbsolutePath();
            System.setProperty("user.dir", launchRoot.getAbsolutePath());

            MtsPatchCacheStore.store(new MTSClassPool());

            assertTrue(new File(cacheRoot, "desktop-1.0-modded.jar").length() >= 1024L * 1024L);
            assertTrue(new File(cacheRoot, ".mts_patch_cache").isFile());
            assertTrue(new File(cacheRoot, "package/Example's Mod-modded.jar").isFile());
            assertFalse(new File(launchRoot, "package/Example's Mod-modded.jar").exists());
            assertEquals(cacheRoot.getAbsolutePath(), PackageJar.observedUserDir);
            assertEquals(launchRoot.getAbsolutePath(), System.getProperty("user.dir"));
        } finally {
            if (previousUserDir == null) {
                System.clearProperty("user.dir");
            } else {
                System.setProperty("user.dir", previousUserDir);
            }
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
            String previousUserDir = System.getProperty("user.dir");

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
            assertEquals(root.getAbsolutePath(), PackageJar.observedUserDir);
            assertEquals(previousUserDir, System.getProperty("user.dir"));
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
    public void store_mergesCompiledBaseGameClassesIntoCacheJar() throws Exception {
        File root = Files.createTempDirectory("mts-patch-cache-store-compiled-classes-").toFile();
        try {
            setCacheProperties(root);
            resetStubTracking();
            PackageJar.writePackageJarFiles = true;
            ByteArrayMapClassPath compiledClasses = new ByteArrayMapClassPath();
            byte[] patchedCardLibrary = "patched-card-library".getBytes("UTF-8");
            byte[] modClass = "mod-class".getBytes("UTF-8");
            compiledClasses.addClass(
                    "com.megacrit.cardcrawl.helpers.CardLibrary",
                    null,
                    patchedCardLibrary
            );
            compiledClasses.addClass(
                    "example.ExampleMod",
                    new java.net.URL("file:/mods/ExampleMod.jar"),
                    modClass
            );

            MtsPatchCacheStore.store(new MTSClassPool(), compiledClasses);

            File cachedJar = new File(root, "desktop-1.0-modded.jar");
            assertTrue(cachedJar.isFile());
            assertTrue(new File(root, ".mts_patch_cache").isFile());
            assertTrue(
                    java.util.Arrays.equals(
                            patchedCardLibrary,
                            readJarEntry(cachedJar, "com/megacrit/cardcrawl/helpers/CardLibrary.class")
                    )
            );
            assertFalse(hasJarEntry(cachedJar, "example/ExampleMod.class"));
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
        Loader.STS_JAR = "";
        Loader.KOTLIN_JAR = "/missing-kotlin.jar";
        Loader.COREPATCHES_JAR = "/missing-corepatches.jar";
        Loader.MODINFOS = new ModInfo[0];
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

    private static byte[] readJarEntry(File jar, String name) throws Exception {
        JarFile jarFile = new JarFile(jar);
        try {
            java.util.jar.JarEntry entry = jarFile.getJarEntry(name);
            if (entry == null) {
                return null;
            }
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            java.io.InputStream input = jarFile.getInputStream(entry);
            try {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
            } finally {
                input.close();
            }
            return output.toByteArray();
        } finally {
            jarFile.close();
        }
    }

    private static boolean hasJarEntry(File jar, String name) throws Exception {
        JarFile jarFile = new JarFile(jar);
        try {
            return jarFile.getJarEntry(name) != null;
        } finally {
            jarFile.close();
        }
    }

    private static void writeJar(File jar, String entryName, String content) throws Exception {
        JarOutputStream output = new JarOutputStream(new FileOutputStream(jar, false));
        try {
            output.putNextEntry(new JarEntry(entryName));
            output.write(content.getBytes("UTF-8"));
            output.closeEntry();
        } finally {
            output.close();
        }
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
