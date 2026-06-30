package io.stamethyst.bridge;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

public final class MtsPatchCacheStore {
    private static final long MIN_CACHE_JAR_BYTES = 1024L * 1024L;
    private static final String PROPERTY_ENABLED = "amethyst.mts.patch_cache.enabled";
    private static final String PROPERTY_JAR = "amethyst.mts.patch_cache.jar";
    private static final String PROPERTY_MARKER = "amethyst.mts.patch_cache.marker";
    private static final String PROPERTY_PACKAGE_DIR = "amethyst.mts.patch_cache.package_dir";
    private static final String PROPERTY_EXPECTED = "amethyst.mts.patch_cache.expected";
    private static final ThreadLocal<Boolean> COMPILE_CAPTURE_RESTORE_OUT_JAR = new ThreadLocal<Boolean>();

    private MtsPatchCacheStore() {
    }

    public static void beginCompileCapture() {
        COMPILE_CAPTURE_RESTORE_OUT_JAR.remove();
        if (!Boolean.parseBoolean(System.getProperty(PROPERTY_ENABLED, "false"))) {
            return;
        }

        try {
            Class<?> mtsLoaderClass = loadMtsLoaderClass(MtsPatchCacheStore.class.getClassLoader());
            Field packageField = mtsLoaderClass.getDeclaredField("PACKAGE");
            Field outJarField = mtsLoaderClass.getDeclaredField("OUT_JAR");
            packageField.setAccessible(true);
            outJarField.setAccessible(true);
            boolean previousPackage = packageField.getBoolean(null);
            boolean previousOutJar = outJarField.getBoolean(null);
            if (!previousPackage && !previousOutJar) {
                outJarField.setBoolean(null, true);
                COMPILE_CAPTURE_RESTORE_OUT_JAR.set(Boolean.TRUE);
                log("Enabled temporary OUT_JAR capture for MTS patch cache");
            }
        } catch (Throwable error) {
            COMPILE_CAPTURE_RESTORE_OUT_JAR.remove();
            log("Failed to enable temporary OUT_JAR capture for MTS patch cache: " + error);
        }
    }

    public static void finishCompileCapture() {
        boolean shouldRestore = Boolean.TRUE.equals(COMPILE_CAPTURE_RESTORE_OUT_JAR.get());
        COMPILE_CAPTURE_RESTORE_OUT_JAR.remove();
        if (!shouldRestore) {
            return;
        }

        try {
            Class<?> mtsLoaderClass = loadMtsLoaderClass(MtsPatchCacheStore.class.getClassLoader());
            Field outJarField = mtsLoaderClass.getDeclaredField("OUT_JAR");
            outJarField.setAccessible(true);
            outJarField.setBoolean(null, false);
        } catch (Throwable error) {
            log("Failed to restore temporary OUT_JAR capture for MTS patch cache: " + error);
        }
    }

    public static void store(Object classPool) {
        if (!Boolean.parseBoolean(System.getProperty(PROPERTY_ENABLED, "false"))) {
            return;
        }
        String expectedMarker = System.getProperty(PROPERTY_EXPECTED, "").trim();
        File cachedJar = new File(System.getProperty(PROPERTY_JAR, ""));
        File markerFile = new File(System.getProperty(PROPERTY_MARKER, ""));
        File packageDir = resolvePackageDir(cachedJar);
        File diagnosticFile = resolveDiagnosticFile(cachedJar);
        if (expectedMarker.length() == 0 || classPool == null) {
            return;
        }

        try {
            deleteIfExists(markerFile);
            deleteIfExists(diagnosticFile);
            deletePackageJars(packageDir);
            File parent = cachedJar.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IllegalStateException("Failed to create cache dir: " + parent.getAbsolutePath());
            }
            if (!packageDir.isDirectory() && !packageDir.mkdirs()) {
                throw new IllegalStateException("Failed to create cache package dir: " + packageDir.getAbsolutePath());
            }
            ClassLoader loader = classPool.getClass().getClassLoader();
            Class<?> packageJarClass = Class.forName(
                    "com.evacipated.cardcrawl.modthespire.PackageJar",
                    false,
                    loader
            );
            Method packageJar = packageJarClass.getDeclaredMethod("packageJar", classPool.getClass(), String.class);
            packageJar.setAccessible(true);
            log("Writing MTS patch cache jar: " + cachedJar.getAbsolutePath());
            writeDiagnostic(
                    diagnosticFile,
                    "start user.dir=" + System.getProperty("user.dir") +
                            " cacheJar=" + cachedJar.getAbsolutePath() +
                            " packageDir=" + packageDir.getAbsolutePath()
            );
            primeOutJarClasses(classPool, diagnosticFile);
            packageJar.invoke(null, classPool, cachedJar.getAbsolutePath());
            writeDiagnostic(
                    diagnosticFile,
                    "after packageJar cacheBytes=" + (cachedJar.isFile() ? cachedJar.length() : 0L) +
                            " packageJars=" + countPackageJars(packageDir)
            );
            createJsonEscapedPackageAliases(packageDir);
            if (!cachedJar.isFile() || cachedJar.length() < MIN_CACHE_JAR_BYTES) {
                throw new IllegalStateException(
                        "Cache jar was not created or is too small: " +
                                cachedJar.getAbsolutePath() +
                                " bytes=" +
                                (cachedJar.isFile() ? cachedJar.length() : 0L)
                );
            }
            int packageJarCount = countPackageJars(packageDir);
            if (packageJarCount == 0) {
                throw new IllegalStateException("Cache package jars were not created: " + packageDir.getAbsolutePath());
            }
            writeMarker(markerFile, expectedMarker);
            log("MTS patch cache is ready: packageJars=" + packageJarCount);
            deleteIfExists(diagnosticFile);
        } catch (Throwable error) {
            deleteIfExists(markerFile);
            deleteIfExists(cachedJar);
            deletePackageJars(packageDir);
            writeFailureDiagnostic(diagnosticFile, error);
            log("Failed to write MTS patch cache: " + error);
            error.printStackTrace(System.out);
        }
    }

    private static File resolvePackageDir(File cachedJar) {
        String raw = System.getProperty(PROPERTY_PACKAGE_DIR, "").trim();
        if (raw.length() != 0) {
            return new File(raw);
        }
        File parent = cachedJar.getParentFile();
        return new File(parent == null ? new File(".") : parent, "package");
    }

    private static File resolveDiagnosticFile(File cachedJar) {
        File parent = cachedJar.getParentFile();
        return new File(parent == null ? new File(".") : parent, "mts_patch_cache_debug.log");
    }

    private static void primeOutJarClasses(Object classPool, File diagnosticFile) throws Exception {
        Class<?> classPoolClass = classPool.getClass();
        ClassLoader loader = classPoolClass.getClassLoader();
        Method getOutJarClasses = classPoolClass.getMethod("getOutJarClasses");
        Object outJarClasses = getOutJarClasses.invoke(classPool);
        if (outJarClasses != null) {
            writeDiagnostic(
                    diagnosticFile,
                    "reusing existing outJar outJarCount=" + collectionSize(outJarClasses)
            );
            return;
        }

        Method getModifiedClasses = classPoolClass.getMethod("getModifiedClasses");
        Class<?> mtsLoaderClass = loadMtsLoaderClass(loader);
        Field packageField = mtsLoaderClass.getDeclaredField("PACKAGE");
        Field outJarField = mtsLoaderClass.getDeclaredField("OUT_JAR");
        packageField.setAccessible(true);
        outJarField.setAccessible(true);
        boolean previousPackage = packageField.getBoolean(null);
        boolean previousOutJar = outJarField.getBoolean(null);
        Object modifiedClasses;
        try {
            if (!previousPackage && !previousOutJar) {
                packageField.setBoolean(null, true);
            }
            modifiedClasses = getModifiedClasses.invoke(classPool);
            outJarClasses = getOutJarClasses.invoke(classPool);
        } finally {
            packageField.setBoolean(null, previousPackage);
            outJarField.setBoolean(null, previousOutJar);
        }
        if (outJarClasses == null) {
            throw new IllegalStateException("MTSClassPool.getOutJarClasses() returned null while preparing cache");
        }
        writeDiagnostic(
                diagnosticFile,
                "primed outJar modifiedCount=" + collectionSize(modifiedClasses) +
                        " outJarCount=" + collectionSize(outJarClasses)
        );
    }

    private static Class<?> loadMtsLoaderClass(ClassLoader preferredLoader) throws ClassNotFoundException {
        ClassNotFoundException failure = null;
        ClassLoader[] candidates = new ClassLoader[] {
                preferredLoader,
                Thread.currentThread().getContextClassLoader(),
                MtsPatchCacheStore.class.getClassLoader()
        };
        for (ClassLoader loader : candidates) {
            if (loader == null) {
                continue;
            }
            try {
                return Class.forName("com.evacipated.cardcrawl.modthespire.Loader", false, loader);
            } catch (ClassNotFoundException error) {
                failure = error;
            }
        }
        throw failure == null
                ? new ClassNotFoundException("com.evacipated.cardcrawl.modthespire.Loader")
                : failure;
    }

    private static int countPackageJars(File packageDir) {
        File[] files = packageDir.isDirectory() ? packageDir.listFiles() : null;
        if (files == null) {
            return 0;
        }
        int count = 0;
        for (File file : files) {
            if (isJar(file)) {
                count++;
            }
        }
        return count;
    }

    private static void deletePackageJars(File packageDir) {
        File[] files = packageDir.isDirectory() ? packageDir.listFiles() : null;
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (isJar(file)) {
                file.delete();
            }
        }
    }

    private static boolean isJar(File file) {
        return file.isFile() && file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".jar");
    }

    private static void createJsonEscapedPackageAliases(File packageDir) throws Exception {
        File[] files = packageDir.isDirectory() ? packageDir.listFiles() : null;
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (!isJar(file) || file.getName().indexOf('\'') < 0) {
                continue;
            }
            File alias = new File(packageDir, file.getName().replace("'", "u0027"));
            if (!alias.exists()) {
                copyFile(file, alias);
            }
        }
    }

    private static void copyFile(File source, File target) throws Exception {
        FileInputStream input = new FileInputStream(source);
        try {
            FileOutputStream output = new FileOutputStream(target, false);
            try {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
            } finally {
                output.close();
            }
        } finally {
            input.close();
        }
    }

    private static void writeMarker(File markerFile, String expectedMarker) throws Exception {
        File parent = markerFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Failed to create marker dir: " + parent.getAbsolutePath());
        }
        FileOutputStream output = new FileOutputStream(markerFile, false);
        try {
            output.write(expectedMarker.getBytes(StandardCharsets.UTF_8));
            output.write('\n');
        } finally {
            output.close();
        }
    }

    private static void deleteIfExists(File file) {
        if (file.exists()) {
            file.delete();
        }
    }

    private static int collectionSize(Object value) {
        return value instanceof Collection ? ((Collection<?>) value).size() : -1;
    }

    private static void writeFailureDiagnostic(File diagnosticFile, Throwable error) {
        Throwable cause = error instanceof InvocationTargetException && ((InvocationTargetException) error).getCause() != null
                ? ((InvocationTargetException) error).getCause()
                : error;
        StringWriter stackTrace = new StringWriter();
        cause.printStackTrace(new PrintWriter(stackTrace));
        writeDiagnostic(
                diagnosticFile,
                "failed error=" + error + "\nrootCause=" + cause + "\n" + stackTrace
        );
    }

    private static void writeDiagnostic(File diagnosticFile, String message) {
        try {
            File parent = diagnosticFile.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            FileOutputStream output = new FileOutputStream(diagnosticFile, true);
            try {
                output.write(message.getBytes(StandardCharsets.UTF_8));
                output.write('\n');
            } finally {
                output.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void log(String message) {
        System.out.println("[Amethyst] " + message);
    }
}
