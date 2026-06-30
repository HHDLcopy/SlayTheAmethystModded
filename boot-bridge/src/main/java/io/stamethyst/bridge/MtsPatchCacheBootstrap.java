package io.stamethyst.bridge;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MtsPatchCacheBootstrap {
    private static final String PROPERTY_ENABLED = "amethyst.mts.patch_cache.enabled";
    private static final String PROPERTY_CURRENT = "amethyst.mts.patch_cache.current";
    private static final String PROPERTY_JAR = "amethyst.mts.patch_cache.jar";
    private static final String PROPERTY_MARKER = "amethyst.mts.patch_cache.marker";
    private static final String PROPERTY_PACKAGE_DIR = "amethyst.mts.patch_cache.package_dir";
    private static final String PROPERTY_EXPECTED = "amethyst.mts.patch_cache.expected";
    private static final long MIN_CACHE_JAR_BYTES = 1024L * 1024L;
    private static final String PREPACKAGED_LAUNCHER =
            "com.evacipated.cardcrawl.modthespire.PackageJar$PrepackagedLauncher";
    private static final String MTS_LOADER = "com.evacipated.cardcrawl.modthespire.Loader";
    private static final String MTS_MOD_INFO = "com.evacipated.cardcrawl.modthespire.ModInfo";
    private static final String MTS_PATCHER = "com.evacipated.cardcrawl.modthespire.Patcher";
    private static final String GAME_SETTINGS = "com.megacrit.cardcrawl.core.Settings";

    private MtsPatchCacheBootstrap() {
    }

    public static boolean launchIfCurrent() {
        if (!Boolean.parseBoolean(System.getProperty(PROPERTY_ENABLED, "false"))) {
            return false;
        }
        if (!Boolean.parseBoolean(System.getProperty(PROPERTY_CURRENT, "false"))) {
            return false;
        }

        File cachedJar = new File(System.getProperty(PROPERTY_JAR, ""));
        File markerFile = new File(System.getProperty(PROPERTY_MARKER, ""));
        File packageDir = resolvePackageDir();
        String expectedMarker = System.getProperty(PROPERTY_EXPECTED, "").trim();
        if (expectedMarker.length() == 0 || !cachedJar.isFile() || cachedJar.length() < MIN_CACHE_JAR_BYTES) {
            log("Patch cache miss: cached jar is missing");
            return false;
        }
        if (!hasPackageJars(packageDir)) {
            log("Patch cache miss: package jars are missing");
            return false;
        }
        if (!markerMatches(markerFile, expectedMarker)) {
            log("Patch cache miss: marker changed");
            return false;
        }

        try {
            log("Launching cached MTS patch jar: " + cachedJar.getAbsolutePath());
            invokeCachedLauncher(cachedJar, packageDir, readMtsArgs());
            return true;
        } catch (Throwable error) {
            log("Patch cache launch failed, falling back to ModTheSpire patching: " + error);
            error.printStackTrace(System.out);
            return false;
        }
    }

    private static boolean markerMatches(File markerFile, String expectedMarker) {
        try {
            if (!markerFile.isFile()) {
                return false;
            }
            String actual = new String(Files.readAllBytes(markerFile.toPath()), StandardCharsets.UTF_8).trim();
            return expectedMarker.equals(actual);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static File resolvePackageDir() {
        String raw = System.getProperty(PROPERTY_PACKAGE_DIR, "").trim();
        return raw.length() == 0 ? new File("package") : new File(raw);
    }

    private static boolean hasPackageJars(File packageDir) {
        File[] files = packageDir.isDirectory() ? packageDir.listFiles() : null;
        if (files == null) {
            return false;
        }
        for (File file : files) {
            if (file.isFile() && file.length() > 0L && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return true;
            }
        }
        return false;
    }

    private static String[] readMtsArgs() {
        try {
            Class<?> loader = Class.forName(MTS_LOADER);
            Object rawArgs = loader.getField("ARGS").get(null);
            if (rawArgs instanceof String[]) {
                return (String[]) rawArgs;
            }
        } catch (Throwable ignored) {
        }
        return new String[0];
    }

    public static void preparePrepackagedLaunch() throws Throwable {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = MtsPatchCacheBootstrap.class.getClassLoader();
        }

        markSettingsAsModded(loader);

        Class<?> mtsLoader = Class.forName(MTS_LOADER, false, loader);
        Object modInfos = mtsLoader.getField("MODINFOS").get(null);
        if (modInfos == null || !modInfos.getClass().isArray()) {
            log("Skipping cached MTS annotation DB preparation: no mod infos");
            return;
        }

        Class<?> modInfoClass = Class.forName(MTS_MOD_INFO, false, loader);
        Class<?> modInfoArrayClass = Array.newInstance(modInfoClass, 0).getClass();
        Class<?> patcher = Class.forName(MTS_PATCHER, false, loader);
        invokeStatic(patcher.getMethod("findPatches", modInfoArrayClass), modInfos);
        invokeStatic(patcher.getMethod("initializeMods", ClassLoader.class, modInfoArrayClass), loader, modInfos);
        log("Prepared cached MTS launch state: mods=" + Array.getLength(modInfos));
    }

    private static void markSettingsAsModded(ClassLoader loader) throws Throwable {
        try {
            Class<?> settings = Class.forName(GAME_SETTINGS, false, loader);
            settings.getDeclaredField("isModded").set(null, Boolean.TRUE);
            settings.getDeclaredField("isDev").set(null, Boolean.FALSE);
        } catch (ClassNotFoundException ignored) {
        } catch (NoSuchFieldException ignored) {
        }
    }

    private static void invokeStatic(Method method, Object... args) throws Throwable {
        try {
            method.invoke(null, args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            throw cause == null ? error : cause;
        }
    }

    private static void invokeCachedLauncher(File cachedJar, File packageDir, String[] args) throws Throwable {
        URL[] urls = buildCacheUrls(cachedJar, packageDir);
        ChildFirstJarClassLoader loader = new ChildFirstJarClassLoader(
                urls,
                MtsPatchCacheBootstrap.class.getClassLoader()
        );
        Thread thread = Thread.currentThread();
        ClassLoader previousContextLoader = thread.getContextClassLoader();
        String previousUserDir = System.getProperty("user.dir");
        try {
            thread.setContextClassLoader(loader);
            File cacheRoot = cachedJar.getParentFile();
            if (cacheRoot != null) {
                System.setProperty("user.dir", cacheRoot.getAbsolutePath());
            }
            Class<?> launcher = Class.forName(PREPACKAGED_LAUNCHER, true, loader);
            Method main = launcher.getMethod("main", String[].class);
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            throw cause == null ? error : cause;
        } finally {
            if (previousUserDir == null) {
                System.clearProperty("user.dir");
            } else {
                System.setProperty("user.dir", previousUserDir);
            }
            thread.setContextClassLoader(previousContextLoader);
        }
    }

    private static URL[] buildCacheUrls(File cachedJar, File packageDir) throws Exception {
        List<URL> urls = new ArrayList<URL>();
        urls.add(cachedJar.toURI().toURL());
        File[] files = packageDir.isDirectory() ? packageDir.listFiles() : null;
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    urls.add(file.toURI().toURL());
                }
            }
        }
        return urls.toArray(new URL[urls.size()]);
    }

    private static void log(String message) {
        System.out.println("[Amethyst] " + message);
    }

    private static final class ChildFirstJarClassLoader extends URLClassLoader {
        ChildFirstJarClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (isParentFirst(name)) {
                return super.loadClass(name, resolve);
            }

            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                try {
                    loaded = findClass(name);
                } catch (ClassNotFoundException ignored) {
                    loaded = super.loadClass(name, false);
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }

        private boolean isParentFirst(String name) {
            return name.startsWith("java.") ||
                    name.startsWith("javax.") ||
                    name.startsWith("sun.") ||
                    name.startsWith("jdk.") ||
                    name.startsWith("com.badlogic.gdx.") ||
                    name.startsWith("org.lwjgl.") ||
                    name.startsWith("org.apache.logging.log4j.") ||
                    name.startsWith("io.stamethyst.bridge.");
        }
    }
}
