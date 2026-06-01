package io.stamethyst.bridge;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class MtsModFileListOverride {
    public static final String PROPERTY_NAME = "amethyst.mts.mod_file_list";

    private MtsModFileListOverride() {
    }

    public static File[] resolve(File[] fallback) {
        String rawListPath = System.getProperty(PROPERTY_NAME);
        if (rawListPath == null || rawListPath.trim().isEmpty()) {
            return fallbackOrEmpty(fallback);
        }

        File listFile = new File(rawListPath.trim());
        if (!listFile.isFile()) {
            log("MTS mod file list not found: " + listFile.getAbsolutePath());
            return fallbackOrEmpty(fallback);
        }

        List<File> files = new ArrayList<File>();
        LinkedHashSet<String> seenPaths = new LinkedHashSet<String>();
        try {
            List<String> lines = Files.readAllLines(listFile.toPath(), StandardCharsets.UTF_8);
            for (String rawLine : lines) {
                String path = rawLine == null ? "" : rawLine.trim();
                if (path.isEmpty() || path.startsWith("#")) {
                    continue;
                }
                File file = new File(path);
                if (!file.isFile()) {
                    log("Skipping missing MTS mod jar: " + file.getAbsolutePath());
                    continue;
                }
                if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    log("Skipping non-jar MTS mod file: " + file.getAbsolutePath());
                    continue;
                }
                String absolutePath = file.getAbsolutePath();
                if (seenPaths.add(absolutePath)) {
                    files.add(file);
                }
            }
        } catch (IOException error) {
            log("Failed to read MTS mod file list: " + error.getMessage());
            return fallbackOrEmpty(fallback);
        } catch (RuntimeException error) {
            log("Failed to parse MTS mod file list: " + error.getMessage());
            return fallbackOrEmpty(fallback);
        }

        if (files.isEmpty()) {
            log("MTS mod file list was empty after validation; using original scan result");
            return fallbackOrEmpty(fallback);
        }
        log("Using MTS mod file list with " + files.size() + " jar(s)");
        return files.toArray(new File[files.size()]);
    }

    private static File[] fallbackOrEmpty(File[] fallback) {
        return fallback != null ? fallback : new File[0];
    }

    private static void log(String message) {
        System.out.println("[Amethyst] " + message);
    }
}
