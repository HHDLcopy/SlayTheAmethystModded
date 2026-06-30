package io.stamethyst.bridge;

import org.junit.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Random;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MtsPatchCacheBootstrapTest {
    private static final String PROP_ENABLED = "amethyst.mts.patch_cache.enabled";
    private static final String PROP_CURRENT = "amethyst.mts.patch_cache.current";
    private static final String PROP_JAR = "amethyst.mts.patch_cache.jar";
    private static final String PROP_MARKER = "amethyst.mts.patch_cache.marker";
    private static final String PROP_PACKAGE_DIR = "amethyst.mts.patch_cache.package_dir";
    private static final String PROP_EXPECTED = "amethyst.mts.patch_cache.expected";
    private static final String PROP_LAUNCHED = "amethyst.test.patch_cache.launched";
    private static final String PROP_LAUNCHED_DIR = "amethyst.test.patch_cache.user_dir";
    private static final String PROP_PREPARED = "amethyst.test.patch_cache.prepared";
    private static final String PROP_INITIALIZED = "amethyst.test.patch_cache.initialized";
    private static final String PROP_IS_MODDED = "amethyst.test.patch_cache.is_modded";
    private static final String PROP_IS_DEV = "amethyst.test.patch_cache.is_dev";

    @Test
    public void launchIfCurrent_invokesCachedPrepackagedLauncher() throws Exception {
        File root = Files.createTempDirectory("mts-patch-cache-bootstrap-").toFile();
        try {
            File cachedJar = buildFakePrepackagedJar(root);
            File marker = new File(root, ".mts_patch_cache");
            File packageDir = new File(root, "package");
            Files.write(marker.toPath(), "expected\n".getBytes(StandardCharsets.UTF_8));
            writeFakePackageJar(packageDir);

            setCacheProperties(cachedJar, marker, packageDir, "expected");

            assertTrue(MtsPatchCacheBootstrap.launchIfCurrent());
            assertEquals("0", System.getProperty(PROP_LAUNCHED));
            assertEquals(root.getAbsolutePath(), System.getProperty(PROP_LAUNCHED_DIR));
        } finally {
            clearCacheProperties();
            deleteRecursively(root);
        }
    }

    @Test
    public void launchIfCurrent_returnsFalseWhenMarkerDoesNotMatch() throws Exception {
        File root = Files.createTempDirectory("mts-patch-cache-bootstrap-miss-").toFile();
        try {
            File cachedJar = buildFakePrepackagedJar(root);
            File marker = new File(root, ".mts_patch_cache");
            File packageDir = new File(root, "package");
            Files.write(marker.toPath(), "old\n".getBytes(StandardCharsets.UTF_8));
            writeFakePackageJar(packageDir);

            setCacheProperties(cachedJar, marker, packageDir, "expected");

            assertFalse(MtsPatchCacheBootstrap.launchIfCurrent());
        } finally {
            clearCacheProperties();
            deleteRecursively(root);
        }
    }

    @Test
    public void preparePrepackagedLaunch_initializesModsAndMarksSettings() throws Throwable {
        File root = Files.createTempDirectory("mts-patch-cache-bootstrap-prepare-").toFile();
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        try {
            File jar = buildFakeMtsRuntimeJar(root);
            URLClassLoader cachedLoader = new URLClassLoader(
                    new java.net.URL[]{jar.toURI().toURL()},
                    null
            );
            Thread.currentThread().setContextClassLoader(cachedLoader);
            System.clearProperty(PROP_PREPARED);

            MtsPatchCacheBootstrap.preparePrepackagedLaunch();

            assertEquals("2", System.getProperty(PROP_PREPARED));
            assertEquals("2", System.getProperty(PROP_INITIALIZED));
            assertEquals("true", System.getProperty(PROP_IS_MODDED));
            assertEquals("false", System.getProperty(PROP_IS_DEV));
            cachedLoader.close();
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
            System.clearProperty(PROP_PREPARED);
            System.clearProperty(PROP_INITIALIZED);
            System.clearProperty(PROP_IS_MODDED);
            System.clearProperty(PROP_IS_DEV);
            deleteRecursively(root);
        }
    }

    private static void setCacheProperties(File cachedJar, File marker, File packageDir, String expected) {
        System.setProperty(PROP_ENABLED, "true");
        System.setProperty(PROP_CURRENT, "true");
        System.setProperty(PROP_JAR, cachedJar.getAbsolutePath());
        System.setProperty(PROP_MARKER, marker.getAbsolutePath());
        System.setProperty(PROP_PACKAGE_DIR, packageDir.getAbsolutePath());
        System.setProperty(PROP_EXPECTED, expected);
        System.clearProperty(PROP_LAUNCHED);
    }

    private static void clearCacheProperties() {
        System.clearProperty(PROP_ENABLED);
        System.clearProperty(PROP_CURRENT);
        System.clearProperty(PROP_JAR);
        System.clearProperty(PROP_MARKER);
        System.clearProperty(PROP_PACKAGE_DIR);
        System.clearProperty(PROP_EXPECTED);
        System.clearProperty(PROP_LAUNCHED);
        System.clearProperty(PROP_LAUNCHED_DIR);
    }

    private static File buildFakeMtsRuntimeJar(File root) throws Exception {
        File sourceDir = new File(root, "runtime-src");
        File classDir = new File(root, "runtime-classes");
        File packageDir = new File(sourceDir, "com/evacipated/cardcrawl/modthespire");
        assertTrue(packageDir.mkdirs());
        assertTrue(classDir.mkdirs());

        Files.write(
                new File(packageDir, "ModInfo.java").toPath(),
                (
                        "package com.evacipated.cardcrawl.modthespire;\n" +
                                "public final class ModInfo {}\n"
                ).getBytes(StandardCharsets.UTF_8)
        );
        Files.write(
                new File(packageDir, "Loader.java").toPath(),
                (
                        "package com.evacipated.cardcrawl.modthespire;\n" +
                                "public final class Loader {\n" +
                                "  public static ModInfo[] MODINFOS = new ModInfo[] { new ModInfo(), new ModInfo() };\n" +
                                "}\n"
                ).getBytes(StandardCharsets.UTF_8)
        );
        File settingsDir = new File(sourceDir, "com/megacrit/cardcrawl/core");
        assertTrue(settingsDir.mkdirs());
        Files.write(
                new File(settingsDir, "Settings.java").toPath(),
                (
                        "package com.megacrit.cardcrawl.core;\n" +
                                "public final class Settings {\n" +
                                "  public static boolean isModded;\n" +
                                "  public static boolean isDev = true;\n" +
                                "}\n"
                ).getBytes(StandardCharsets.UTF_8)
        );
        Files.write(
                new File(packageDir, "Patcher.java").toPath(),
                (
                        "package com.evacipated.cardcrawl.modthespire;\n" +
                                "public final class Patcher {\n" +
                                "  public static java.util.List findPatches(ModInfo[] infos) {\n" +
                                "    System.setProperty(\"" + PROP_PREPARED + "\", String.valueOf(infos.length));\n" +
                                "    return java.util.Collections.emptyList();\n" +
                                "  }\n" +
                                "  public static void initializeMods(ClassLoader loader, ModInfo[] infos) {\n" +
                                "    System.setProperty(\"" + PROP_INITIALIZED + "\", String.valueOf(infos.length));\n" +
                                "    try {\n" +
                                "      Class<?> settings = loader.loadClass(\"com.megacrit.cardcrawl.core.Settings\");\n" +
                                "      System.setProperty(\"" + PROP_IS_MODDED + "\", String.valueOf(settings.getField(\"isModded\").getBoolean(null)));\n" +
                                "      System.setProperty(\"" + PROP_IS_DEV + "\", String.valueOf(settings.getField(\"isDev\").getBoolean(null)));\n" +
                                "    } catch (Exception e) {\n" +
                                "      throw new RuntimeException(e);\n" +
                                "    }\n" +
                                "  }\n" +
                                "}\n"
                ).getBytes(StandardCharsets.UTF_8)
        );

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK compiler is required for this test");
        }
        int compileResult = compiler.run(
                null,
                null,
                null,
                "-d",
                classDir.getAbsolutePath(),
                new File(packageDir, "ModInfo.java").getAbsolutePath(),
                new File(packageDir, "Loader.java").getAbsolutePath(),
                new File(packageDir, "Patcher.java").getAbsolutePath(),
                new File(settingsDir, "Settings.java").getAbsolutePath()
        );
        assertEquals(0, compileResult);

        File jar = new File(root, "fake-mts-runtime.jar");
        JarOutputStream jarOut = new JarOutputStream(new FileOutputStream(jar));
        try {
            addClass(jarOut, classDir, "com/evacipated/cardcrawl/modthespire/ModInfo.class");
            addClass(jarOut, classDir, "com/evacipated/cardcrawl/modthespire/Loader.class");
            addClass(jarOut, classDir, "com/evacipated/cardcrawl/modthespire/Patcher.class");
            addClass(jarOut, classDir, "com/megacrit/cardcrawl/core/Settings.class");
        } finally {
            jarOut.close();
        }
        return jar;
    }

    private static File buildFakePrepackagedJar(File root) throws Exception {
        File sourceDir = new File(root, "src");
        File classDir = new File(root, "classes");
        File packageDir = new File(sourceDir, "com/evacipated/cardcrawl/modthespire");
        assertTrue(packageDir.mkdirs());
        assertTrue(classDir.mkdirs());

        File source = new File(packageDir, "PackageJar.java");
        Files.write(
                source.toPath(),
                (
                        "package com.evacipated.cardcrawl.modthespire;\n" +
                                "public final class PackageJar {\n" +
                                "  public static final class PrepackagedLauncher {\n" +
                                "    public static void main(String[] args) {\n" +
                                "      System.setProperty(\"" + PROP_LAUNCHED + "\", String.valueOf(args.length));\n" +
                                "      System.setProperty(\"" + PROP_LAUNCHED_DIR + "\", System.getProperty(\"user.dir\"));\n" +
                                "    }\n" +
                                "  }\n" +
                                "}\n"
                ).getBytes(StandardCharsets.UTF_8)
        );

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK compiler is required for this test");
        }
        int compileResult = compiler.run(null, null, null, "-d", classDir.getAbsolutePath(), source.getAbsolutePath());
        assertEquals(0, compileResult);

        File jar = new File(root, "desktop-1.0-modded.jar");
        JarOutputStream jarOut = new JarOutputStream(new FileOutputStream(jar));
        try {
            addClass(jarOut, classDir, "com/evacipated/cardcrawl/modthespire/PackageJar.class");
            addClass(jarOut, classDir, "com/evacipated/cardcrawl/modthespire/PackageJar$PrepackagedLauncher.class");
            jarOut.putNextEntry(new JarEntry("amethyst-cache-padding.bin"));
            byte[] padding = new byte[1024 * 1024];
            new Random(42L).nextBytes(padding);
            jarOut.write(padding);
            jarOut.closeEntry();
        } finally {
            jarOut.close();
        }
        return jar;
    }

    private static void writeFakePackageJar(File packageDir) throws Exception {
        assertTrue(packageDir.mkdirs() || packageDir.isDirectory());
        File jar = new File(packageDir, "ExampleMod-modded.jar");
        JarOutputStream jarOut = new JarOutputStream(new FileOutputStream(jar));
        try {
            jarOut.putNextEntry(new JarEntry("example/ExampleMod.class"));
            jarOut.write("mod".getBytes(StandardCharsets.UTF_8));
            jarOut.closeEntry();
        } finally {
            jarOut.close();
        }
    }

    private static void addClass(JarOutputStream jarOut, File classDir, String entryName) throws Exception {
        jarOut.putNextEntry(new JarEntry(entryName));
        Files.copy(new File(classDir, entryName).toPath(), jarOut);
        jarOut.closeEntry();
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
