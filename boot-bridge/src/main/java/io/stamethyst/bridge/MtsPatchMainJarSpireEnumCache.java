package io.stamethyst.bridge;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class MtsPatchMainJarSpireEnumCache {
    private static final String CACHE_FILE_NAME = "main-jar-spire-enums.txt";
    private static final String HEADER = "schema=1";
    private static final String ANNOTATION_DB = "org.scannotation.AnnotationDB";
    private static final String SPIRE_ENUM = "com.evacipated.cardcrawl.modthespire.lib.SpireEnum";

    private MtsPatchMainJarSpireEnumCache() {
    }

    static File resolve(File cacheRoot) {
        return new File(cacheRoot, CACHE_FILE_NAME);
    }

    static void delete(File cacheRoot) {
        File cacheFile = resolve(cacheRoot);
        if (cacheFile.isFile() && !cacheFile.delete()) {
            log("Failed to delete stale MTS main jar SpireEnum cache: " + cacheFile.getAbsolutePath());
        }
    }

    static void writeFromPatchedJar(ClassLoader loader, File cacheRoot, File cachedJar) {
        long startedAtNs = System.nanoTime();
        File cacheFile = resolve(cacheRoot);
        try {
            final Set<String> enumClassNames = scanPatchedJar(loader, cachedJar);
            AtomicFileWriter.write(cacheFile, new AtomicFileWriter.ContentWriter() {
                @Override
                public void write(FileOutputStream output) throws IOException {
                    output.write(HEADER.getBytes(StandardCharsets.UTF_8));
                    output.write('\n');
                    for (String className : enumClassNames) {
                        output.write(className.getBytes(StandardCharsets.UTF_8));
                        output.write('\n');
                    }
                }
            });
            log("Wrote cached MTS main jar SpireEnum entries=" + enumClassNames.size() +
                    " took " + elapsedMs(startedAtNs) + "ms");
        } catch (Throwable error) {
            if (cacheFile.isFile()) {
                cacheFile.delete();
            }
            log("Failed to write cached MTS main jar SpireEnum entries: " + error);
        }
    }

    static Set<String> read(File cacheRoot) throws IOException {
        File cacheFile = resolve(cacheRoot);
        if (!cacheFile.isFile()) {
            return null;
        }
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(cacheFile), StandardCharsets.UTF_8)
        );
        try {
            String header = reader.readLine();
            if (!HEADER.equals(header)) {
                throw new IOException("unsupported main jar SpireEnum cache header: " + header);
            }
            LinkedHashSet<String> enumClassNames = new LinkedHashSet<String>();
            String line;
            while ((line = reader.readLine()) != null) {
                String className = line.trim();
                if (className.length() != 0 && className.indexOf('/') < 0) {
                    enumClassNames.add(className);
                }
            }
            return enumClassNames;
        } finally {
            reader.close();
        }
    }

    private static Set<String> scanPatchedJar(ClassLoader loader, File cachedJar) throws Exception {
        Class<?> annotationDbClass = Class.forName(ANNOTATION_DB, false, loader);
        Object db = annotationDbClass.getDeclaredConstructor().newInstance();
        annotationDbClass.getMethod("setScanClassAnnotations", boolean.class).invoke(db, false);
        annotationDbClass.getMethod("setScanMethodAnnotations", boolean.class).invoke(db, false);
        annotationDbClass.getMethod("scanArchives", URL[].class).invoke(
                db,
                (Object) new URL[]{cachedJar.toURI().toURL()}
        );
        Method getAnnotationIndex = annotationDbClass.getMethod("getAnnotationIndex");
        String spireEnumName = Class.forName(SPIRE_ENUM, false, loader).getName();
        return readSpireEnumClasses(getAnnotationIndex, spireEnumName, db);
    }

    private static Set<String> readSpireEnumClasses(
            Method getAnnotationIndex,
            String spireEnumName,
            Object db
    ) throws Exception {
        LinkedHashSet<String> enumClassNames = new LinkedHashSet<String>();
        Object rawIndex = getAnnotationIndex.invoke(db);
        if (!(rawIndex instanceof Map)) {
            return enumClassNames;
        }
        Object rawClasses = ((Map<?, ?>) rawIndex).get(spireEnumName);
        if (!(rawClasses instanceof Iterable)) {
            return enumClassNames;
        }
        for (Object item : (Iterable<?>) rawClasses) {
            if (item instanceof String) {
                enumClassNames.add((String) item);
            }
        }
        return enumClassNames;
    }

    private static long elapsedMs(long startedAtNs) {
        return (System.nanoTime() - startedAtNs) / 1000000L;
    }

    private static void log(String message) {
        System.out.println("[Amethyst] " + message);
    }
}
