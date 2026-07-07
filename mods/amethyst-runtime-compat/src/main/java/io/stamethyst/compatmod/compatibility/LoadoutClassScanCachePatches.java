package io.stamethyst.compatmod.compatibility;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch2;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import io.stamethyst.compatmod.core.StartupCacheRuntimeConfig;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class LoadoutClassScanCachePatches {
    private static final String MOD_ID = "loadout";
    private static final String ENABLED_PROP = "amethyst.runtime_compat.loadout_class_scan_cache";
    private static final String CACHE_DIR_PROP = "amethyst.loadout.scan_cache_dir";
    private static final String MTS_PATCH_CACHE_EXPECTED_PROP = "amethyst.mts.patch_cache.expected";
    private static final int SCHEMA_VERSION = 2;
    private static final Map<String, Field> FIELD_CACHE = new ConcurrentHashMap<String, Field>();
    private static final Map<String, Method> METHOD_CACHE = new ConcurrentHashMap<String, Method>();
    private static volatile boolean fallbackLogged;

    private LoadoutClassScanCachePatches() {
    }

    @SpirePatch2(
        cls = "loadout.util.CardModAdder",
        method = "run",
        requiredModId = MOD_ID,
        optional = true
    )
    public static class CardModAdderRunPatch {
        @SpirePrefixPatch
        public static SpireReturn<Void> Prefix(Object __instance) {
            return runCachedAdder(__instance, Kind.CARD_MOD);
        }
    }

    @SpirePatch2(
        cls = "loadout.LoadoutMod",
        method = "autoAddCardMods",
        requiredModId = MOD_ID,
        optional = true
    )
    public static class AutoAddCardModsPatch {
        @SpirePrefixPatch
        public static SpireReturn<Void> Prefix(Object __instance) {
            if (!StartupCacheRuntimeConfig.isCacheFeatureEnabled(ENABLED_PROP, true)) {
                return SpireReturn.Continue();
            }
            try {
                runCachedAutoAddCardMods(resolveLoadoutClassLoader(__instance));
                return SpireReturn.Return(null);
            } catch (Throwable throwable) {
                logFallbackOnce("Loadout cached autoAddCardMods failed; falling back to Loadout threaded scanner", throwable);
                return SpireReturn.Continue();
            }
        }
    }

    @SpirePatch2(
        cls = "loadout.LoadoutMod",
        method = "autoAddStuffs",
        requiredModId = MOD_ID,
        optional = true
    )
    public static class AutoAddStuffsPatch {
        @SpirePrefixPatch
        public static SpireReturn<Void> Prefix(Object __instance) {
            if (!StartupCacheRuntimeConfig.isCacheFeatureEnabled(ENABLED_PROP, true)) {
                return SpireReturn.Continue();
            }
            try {
                runCachedAutoAddStuffs(resolveLoadoutClassLoader(__instance));
                return SpireReturn.Return(null);
            } catch (Throwable throwable) {
                logFallbackOnce("Loadout cached autoAddStuffs failed; falling back to Loadout threaded scanner", throwable);
                return SpireReturn.Continue();
            }
        }
    }

    @SpirePatch2(
        cls = "loadout.util.PowerAdder",
        method = "run",
        requiredModId = MOD_ID,
        optional = true
    )
    public static class PowerAdderRunPatch {
        @SpirePrefixPatch
        public static SpireReturn<Void> Prefix(Object __instance) {
            return runCachedAdder(__instance, Kind.POWER);
        }
    }

    @SpirePatch2(
        cls = "loadout.util.OrbAdder",
        method = "run",
        requiredModId = MOD_ID,
        optional = true
    )
    public static class OrbAdderRunPatch {
        @SpirePrefixPatch
        public static SpireReturn<Void> Prefix(Object __instance) {
            return runCachedAdder(__instance, Kind.ORB);
        }
    }

    @SpirePatch2(
        cls = "loadout.util.MonsterAdder",
        method = "run",
        requiredModId = MOD_ID,
        optional = true
    )
    public static class MonsterAdderRunPatch {
        @SpirePrefixPatch
        public static SpireReturn<Void> Prefix(Object __instance) {
            return runCachedAdder(__instance, Kind.MONSTER);
        }
    }

    private static SpireReturn<Void> runCachedAdder(Object adder, Kind kind) {
        if (!StartupCacheRuntimeConfig.isCacheFeatureEnabled(ENABLED_PROP, true)) {
            return SpireReturn.Continue();
        }
        long startedAtNs = System.nanoTime();
        try {
            File jarFile = resolveAdderJarFile(adder);
            if (jarFile == null || !jarFile.isFile()) {
                return SpireReturn.Continue();
            }
            ClassLoader loader = resolveLoadoutClassLoader(adder);
            List<String> classNames = getOrBuildClassNames(jarFile, kind, loader);
            Map<Object, Object> targetMap = getLoadoutTargetMap(kind, loader);
            int added = addClassesToMap(classNames, kind, loader, targetMap);
            logThreadFinished(adder, kind, added, classNames.size(), elapsedMs(startedAtNs));
            invokeFinish(adder);
            return SpireReturn.Return(null);
        } catch (Throwable throwable) {
            logFallbackOnce("Loadout cached class scanner failed; falling back to Loadout scanner", throwable);
            return SpireReturn.Continue();
        }
    }

    private static List<String> getOrBuildClassNames(File jarFile, Kind kind, ClassLoader loader)
        throws Exception {
        return getOrBuildClassNames(jarFile, kind, loader, fileCacheIdentity(jarFile));
    }

    private static List<String> getOrBuildClassNames(File jarFile, Kind kind, ClassLoader loader, String cacheIdentity)
        throws Exception {
        File cacheFile = cacheFileFor(cacheIdentity, kind);
        List<String> cached = readCacheFile(cacheFile, cacheIdentity, kind);
        if (cached != null) {
            return cached;
        }
        synchronized (LoadoutClassScanCachePatches.class) {
            cached = readCacheFile(cacheFile, cacheIdentity, kind);
            if (cached != null) {
                return cached;
            }
            Map<Kind, List<String>> built = scanJarForAllKinds(jarFile, loader);
            for (Kind candidateKind : Kind.values()) {
                writeCacheFile(
                    cacheFileFor(cacheIdentity, candidateKind),
                    cacheIdentity,
                    candidateKind,
                    built.get(candidateKind)
                );
            }
            List<String> result = built.get(kind);
            return result == null ? Collections.<String>emptyList() : result;
        }
    }

    private static Map<Kind, List<String>> scanJarForAllKinds(File jarFile, ClassLoader loader)
        throws IOException {
        Map<Kind, List<String>> matches = new EnumMap<Kind, List<String>>(Kind.class);
        for (Kind kind : Kind.values()) {
            matches.put(kind, new ArrayList<String>());
        }
        try (ZipInputStream input = new ZipInputStream(new FileInputStream(jarFile))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (!name.endsWith(".class") || name.startsWith("META-INF/")) {
                    continue;
                }
                String className = name.substring(0, name.length() - ".class".length()).replace('/', '.');
                if ("module-info".equals(className) || className.endsWith(".package-info")) {
                    continue;
                }
                Class<?> candidate;
                try {
                    candidate = loader.loadClass(className);
                } catch (Throwable ignored) {
                    continue;
                }
                if (!isConcretePublicClass(candidate)) {
                    continue;
                }
                for (Kind kind : Kind.values()) {
                    if (kind.accepts(candidate, loader)) {
                        matches.get(kind).add(candidate.getName());
                    }
                }
            }
        }
        for (Kind kind : Kind.values()) {
            Collections.sort(matches.get(kind));
        }
        return matches;
    }

    private static boolean isConcretePublicClass(Class<?> candidate) {
        int modifiers = candidate.getModifiers();
        return Modifier.isPublic(modifiers)
            && !Modifier.isInterface(modifiers)
            && !Modifier.isAbstract(modifiers);
    }

    private static int addClassesToMap(
        List<String> classNames,
        Kind kind,
        ClassLoader loader,
        Map<Object, Object> targetMap
    ) {
        int added = 0;
        for (String className : classNames) {
            try {
                Class<?> cls = loader.loadClass(className);
                if (!kind.accepts(cls, loader)) {
                    continue;
                }
                if (!targetMap.containsKey(cls.getName())) {
                    targetMap.put(cls.getName(), cls);
                    added++;
                }
            } catch (Throwable ignored) {
                // A class that cannot be resolved on this run should behave like Loadout's original scanner.
            }
        }
        return added;
    }

    private static void runCachedAutoAddCardMods(ClassLoader loader) throws Exception {
        long startedAtNs = System.nanoTime();
        Map<Object, Object> targetMap = getLoadoutTargetMap(Kind.CARD_MOD, loader);
        targetMap.clear();
        Object[] modInfos = resolveModInfos(loader);
        int added = 0;
        int matched = 0;
        for (Object modInfo : modInfos) {
            File jarFile = resolveModInfoJarFile(modInfo);
            if (jarFile == null || !jarFile.isFile()) {
                continue;
            }
            List<String> classNames = getOrBuildClassNames(
                jarFile,
                Kind.CARD_MOD,
                loader,
                modInfoCacheIdentity(modInfo, jarFile)
            );
            matched += classNames.size();
            added += addClassesToMap(classNames, Kind.CARD_MOD, loader, targetMap);
        }
        logLoadoutInfo(
            loader,
            "Finished cached autoAddCardMods! added="
                + added
                + " matched="
                + matched
                + " mods="
                + modInfos.length
                + " took="
                + elapsedMs(startedAtNs)
                + "ms"
        );
    }

    private static void runCachedAutoAddStuffs(ClassLoader loader) throws Exception {
        long startedAtNs = System.nanoTime();
        Object[] modInfos = resolveModInfos(loader);
        logLoadoutInfo(loader, "Adding stuffs...");
        int powerAdded = 0;
        int powerMatched = 0;
        int monsterAdded = 0;
        int monsterMatched = 0;
        int orbAdded = 0;
        int orbMatched = 0;
        Map<Object, Object> powerMap = getLoadoutTargetMap(Kind.POWER, loader);
        Map<Object, Object> monsterMap = getLoadoutTargetMap(Kind.MONSTER, loader);
        Map<Object, Object> orbMap = getLoadoutTargetMap(Kind.ORB, loader);
        for (Object modInfo : modInfos) {
            File jarFile = resolveModInfoJarFile(modInfo);
            if (jarFile == null || !jarFile.isFile()) {
                continue;
            }
            String cacheIdentity = modInfoCacheIdentity(modInfo, jarFile);
            List<String> powers = getOrBuildClassNames(jarFile, Kind.POWER, loader, cacheIdentity);
            powerMatched += powers.size();
            powerAdded += addClassesToMap(powers, Kind.POWER, loader, powerMap);

            List<String> monsters = getOrBuildClassNames(jarFile, Kind.MONSTER, loader, cacheIdentity);
            monsterMatched += monsters.size();
            monsterAdded += addClassesToMap(monsters, Kind.MONSTER, loader, monsterMap);

            List<String> orbs = getOrBuildClassNames(jarFile, Kind.ORB, loader, cacheIdentity);
            orbMatched += orbs.size();
            orbAdded += addClassesToMap(orbs, Kind.ORB, loader, orbMap);
        }
        setLoadoutIntField(loader, "numThreadsTotal", 0);
        setLoadoutIntField(loader, "numThreadsFinished", 0);
        logLoadoutInfo(
            loader,
            "Finished cached autoAddStuffs! powerAdded="
                + powerAdded
                + " powerMatched="
                + powerMatched
                + " monsterAdded="
                + monsterAdded
                + " monsterMatched="
                + monsterMatched
                + " orbAdded="
                + orbAdded
                + " orbMatched="
                + orbMatched
                + " mods="
                + modInfos.length
                + " took="
                + elapsedMs(startedAtNs)
                + "ms"
        );
        logLoadoutInfo(loader, "Fnished auto adding stuff! Time Elapsed: " + elapsedMs(startedAtNs) + "ms");
    }

    private static File resolveAdderJarFile(Object adder) throws Exception {
        Object finder = getFieldValue(adder, "finder");
        if (finder == null) {
            return null;
        }
        Object places = getFieldValue(finder, "placesToSearch");
        if (places instanceof LinkedHashMap) {
            LinkedHashMap<?, ?> map = (LinkedHashMap<?, ?>)places;
            for (Object value : map.values()) {
                if (value instanceof File) {
                    return (File)value;
                }
            }
            for (Object key : map.keySet()) {
                if (key instanceof String) {
                    return new File((String)key);
                }
            }
        }
        return null;
    }

    private static ClassLoader resolveLoadoutClassLoader(Object adder) {
        try {
            Object value = getStaticFieldValue(
                Class.forName("loadout.util.AbstractAdder", false, adder.getClass().getClassLoader()),
                "clazzLoader"
            );
            if (value instanceof ClassLoader) {
                return (ClassLoader)value;
            }
        } catch (Throwable ignored) {
        }
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader != null) {
            return contextLoader;
        }
        return adder.getClass().getClassLoader();
    }

    private static ClassLoader resolveDefaultClassLoader() {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader != null) {
            return contextLoader;
        }
        return LoadoutClassScanCachePatches.class.getClassLoader();
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> getLoadoutTargetMap(Kind kind, ClassLoader loader) throws Exception {
        Class<?> loadoutMod = Class.forName("loadout.LoadoutMod", false, loader);
        Object value = getStaticFieldValue(loadoutMod, kind.mapFieldName);
        if (value instanceof Map) {
            return (Map<Object, Object>)value;
        }
        throw new IllegalStateException("Loadout map field is not a map: " + kind.mapFieldName);
    }

    private static void invokeFinish(Object adder) throws Exception {
        getMethod(adder.getClass(), "finish").invoke(adder);
    }

    private static void logThreadFinished(Object adder, Kind kind, int added, int matched, long elapsedMs) {
        try {
            String threadName = String.valueOf(getFieldValue(adder, "threadName"));
            logLoadoutInfo(
                adder.getClass().getClassLoader(),
                "Thread "
                    + threadName
                    + " finished cached "
                    + kind.logName
                    + "! added="
                    + added
                    + " matched="
                    + matched
                    + " took="
                    + elapsedMs
                    + "ms"
            );
        } catch (Throwable ignored) {
        }
    }

    private static void logLoadoutInfo(ClassLoader loader, String message) {
        try {
            Object logger = getStaticFieldValue(
                Class.forName("loadout.LoadoutMod", false, loader),
                "logger"
            );
            Method info = logger.getClass().getMethod("info", String.class);
            info.invoke(logger, message);
        } catch (Throwable ignored) {
        }
    }

    private static Object[] resolveModInfos(ClassLoader loader) throws Exception {
        Object value = getStaticFieldValue(
            Class.forName("com.evacipated.cardcrawl.modthespire.Loader", false, loader),
            "MODINFOS"
        );
        if (value instanceof Object[]) {
            return (Object[])value;
        }
        return new Object[0];
    }

    private static File resolveModInfoJarFile(Object modInfo) throws Exception {
        if (modInfo == null) {
            return null;
        }
        Object value = getFieldValue(modInfo, "jarURL");
        if (value instanceof URL) {
            return new File(((URL)value).toURI());
        }
        return null;
    }

    private static void setLoadoutIntField(ClassLoader loader, String fieldName, int value) {
        try {
            Field field = getField(Class.forName("loadout.LoadoutMod", false, loader), fieldName);
            field.setInt(null, value);
        } catch (Throwable ignored) {
        }
    }

    private static List<String> readCacheFile(File cacheFile, String cacheIdentity, Kind kind) throws IOException {
        if (!cacheFile.isFile()) {
            return null;
        }
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(cacheFile), StandardCharsets.UTF_8)
        );
        try {
            if (!("schema=" + SCHEMA_VERSION).equals(reader.readLine())) {
                return null;
            }
            if (!("kind=" + kind.name()).equals(reader.readLine())) {
                return null;
            }
            if (!("identity=" + cacheIdentity).equals(reader.readLine())) {
                return null;
            }
            List<String> classNames = new ArrayList<String>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() > 0) {
                    classNames.add(line);
                }
            }
            return classNames;
        } finally {
            reader.close();
        }
    }

    private static void writeCacheFile(File cacheFile, String cacheIdentity, Kind kind, List<String> classNames) {
        if (classNames == null) {
            classNames = Collections.emptyList();
        }
        File parent = cacheFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return;
        }
        File tempFile = new File(cacheFile.getParentFile(), cacheFile.getName() + ".tmp");
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(tempFile), StandardCharsets.UTF_8)
            );
            writer.write("schema=" + SCHEMA_VERSION);
            writer.newLine();
            writer.write("kind=" + kind.name());
            writer.newLine();
            writer.write("identity=" + cacheIdentity);
            writer.newLine();
            for (String className : classNames) {
                writer.write(className);
                writer.newLine();
            }
            writer.close();
            writer = null;
            if (cacheFile.isFile() && !cacheFile.delete()) {
                tempFile.delete();
                return;
            }
            if (!tempFile.renameTo(cacheFile)) {
                tempFile.delete();
            }
        } catch (IOException ignored) {
            tempFile.delete();
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static File cacheFileFor(String cacheIdentity, Kind kind) throws Exception {
        return new File(resolveCacheRoot(), fingerprint(cacheIdentity) + "-" + kind.name().toLowerCase() + ".txt");
    }

    private static File resolveCacheRoot() {
        String configured = System.getProperty(CACHE_DIR_PROP);
        if (configured != null && configured.trim().length() > 0) {
            return new File(configured.trim());
        }
        String userDir = System.getProperty("user.dir");
        if (userDir != null && userDir.trim().length() > 0) {
            return new File(new File(userDir, "mts_patch_cache"), "loadout-scan-cache");
        }
        return new File(new File(System.getProperty("java.io.tmpdir", "."), "amethyst"), "loadout-scan-cache");
    }

    private static String modInfoCacheIdentity(Object modInfo, File jarFile) throws Exception {
        String mtsPatchMarker = trimToEmpty(System.getProperty(MTS_PATCH_CACHE_EXPECTED_PROP));
        if (mtsPatchMarker.length() <= 0) {
            return fileCacheIdentity(jarFile);
        }
        String id = stringFieldValue(modInfo, "ID");
        if (id.length() <= 0) {
            id = normalizedModFileName(jarFile);
        }
        String version = stringFieldValue(modInfo, "ModVersion");
        return "mts|" + mtsPatchMarker + "|" + id + "|" + version;
    }

    private static String fileCacheIdentity(File jarFile) {
        return "file|"
            + jarFile.getAbsolutePath()
            + "|"
            + jarFile.length()
            + "|"
            + jarFile.lastModified();
    }

    private static String normalizedModFileName(File jarFile) {
        String name = jarFile.getName();
        if (name.endsWith(".jar")) {
            name = name.substring(0, name.length() - ".jar".length());
        }
        if (name.endsWith("-modded")) {
            name = name.substring(0, name.length() - "-modded".length());
        }
        return name;
    }

    private static String stringFieldValue(Object instance, String fieldName) {
        try {
            Object value = getFieldValue(instance, fieldName);
            return value == null ? "" : String.valueOf(value);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String fingerprint(String identity) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        updateDigest(digest, identity);
        byte[] bytes = digest.digest();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 12 && i < bytes.length; i++) {
            int byteValue = bytes[i] & 0xFF;
            if (byteValue < 16) {
                builder.append('0');
            }
            builder.append(Integer.toHexString(byteValue));
        }
        return builder.toString();
    }

    private static void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte)0);
    }

    private static Object getFieldValue(Object instance, String fieldName) throws Exception {
        Field field = getField(instance.getClass(), fieldName);
        return field.get(instance);
    }

    private static Object getStaticFieldValue(Class<?> cls, String fieldName) throws Exception {
        Field field = getField(cls, fieldName);
        return field.get(null);
    }

    private static Field getField(Class<?> cls, String fieldName) throws NoSuchFieldException {
        String key = cls.getName() + "#" + fieldName;
        Field cached = FIELD_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Class<?> current = cls;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                FIELD_CACHE.put(key, field);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(key);
    }

    private static Method getMethod(Class<?> cls, String methodName) throws NoSuchMethodException {
        String key = cls.getName() + "#" + methodName;
        Method cached = METHOD_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Class<?> current = cls;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(methodName);
                method.setAccessible(true);
                METHOD_CACHE.put(key, method);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(key);
    }

    private static void logFallbackOnce(String message, Throwable throwable) {
        if (fallbackLogged) {
            return;
        }
        fallbackLogged = true;
        System.out.println(
            "[amethyst-runtime-compat] "
                + message
                + ": "
                + throwable.getClass().getSimpleName()
                + ": "
                + throwable.getMessage()
        );
    }

    private static long elapsedMs(long startedAtNs) {
        return (System.nanoTime() - startedAtNs) / 1_000_000L;
    }

    private enum Kind {
        CARD_MOD("basemod.abstracts.AbstractCardModifier", "cardModMap", "Card Mods"),
        POWER("com.megacrit.cardcrawl.powers.AbstractPower", "powersToDisplay", "power"),
        ORB("com.megacrit.cardcrawl.orbs.AbstractOrb", "orbMap", "orbs"),
        MONSTER("com.megacrit.cardcrawl.monsters.AbstractMonster", "monsterMap", "monster");

        final String superClassName;
        final String mapFieldName;
        final String logName;
        private volatile Class<?> superClass;

        Kind(String superClassName, String mapFieldName, String logName) {
            this.superClassName = superClassName;
            this.mapFieldName = mapFieldName;
            this.logName = logName;
        }

        boolean accepts(Class<?> candidate, ClassLoader loader) {
            try {
                Class<?> resolvedSuper = superClass;
                if (resolvedSuper == null) {
                    resolvedSuper = Class.forName(superClassName, false, loader);
                    superClass = resolvedSuper;
                }
                return resolvedSuper.isAssignableFrom(candidate);
            } catch (Throwable ignored) {
                return false;
            }
        }
    }
}
