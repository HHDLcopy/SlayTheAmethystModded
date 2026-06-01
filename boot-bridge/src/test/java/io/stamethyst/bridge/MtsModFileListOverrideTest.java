package io.stamethyst.bridge;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class MtsModFileListOverrideTest {
    @Test
    public void resolveUsesValidatedJarListInOrder() throws Exception {
        PropertyHarness harness = PropertyHarness.create();
        try {
            File first = Files.write(harness.tempDir.toPath().resolve("BaseMod.jar"), new byte[]{1}).toFile();
            File second = Files.write(harness.tempDir.toPath().resolve("Alpha.jar"), new byte[]{2}).toFile();
            File nonJar = Files.write(harness.tempDir.toPath().resolve("notes.txt"), new byte[]{3}).toFile();
            File missing = new File(harness.tempDir, "Missing.jar");
            File list = new File(harness.tempDir, ".mts_mod_file_list");
            List<String> lines = Arrays.asList(
                    "# comment",
                    "",
                    first.getAbsolutePath(),
                    missing.getAbsolutePath(),
                    nonJar.getAbsolutePath(),
                    second.getAbsolutePath(),
                    first.getAbsolutePath()
            );
            Files.write(list.toPath(), lines, StandardCharsets.UTF_8);
            System.setProperty(MtsModFileListOverride.PROPERTY_NAME, list.getAbsolutePath());

            File[] resolved = MtsModFileListOverride.resolve(new File[]{missing});

            assertEquals(2, resolved.length);
            assertEquals(first.getAbsolutePath(), resolved[0].getAbsolutePath());
            assertEquals(second.getAbsolutePath(), resolved[1].getAbsolutePath());
        } finally {
            harness.close();
        }
    }

    @Test
    public void resolveFallsBackWhenListIsUnavailable() throws Exception {
        PropertyHarness harness = PropertyHarness.create();
        try {
            File fallback = new File(harness.tempDir, "fallback.jar");
            System.setProperty(
                    MtsModFileListOverride.PROPERTY_NAME,
                    new File(harness.tempDir, "missing-list.txt").getAbsolutePath()
            );

            File[] resolved = MtsModFileListOverride.resolve(new File[]{fallback});

            assertArrayEquals(new File[]{fallback}, resolved);
        } finally {
            harness.close();
        }
    }

    private static final class PropertyHarness implements AutoCloseable {
        private final File tempDir;
        private final String previousListPath;

        private PropertyHarness(File tempDir, String previousListPath) {
            this.tempDir = tempDir;
            this.previousListPath = previousListPath;
        }

        private static PropertyHarness create() throws Exception {
            return new PropertyHarness(
                    Files.createTempDirectory("mts-mod-file-list-override").toFile(),
                    System.getProperty(MtsModFileListOverride.PROPERTY_NAME)
            );
        }

        @Override
        public void close() throws Exception {
            if (previousListPath == null) {
                System.clearProperty(MtsModFileListOverride.PROPERTY_NAME);
            } else {
                System.setProperty(MtsModFileListOverride.PROPERTY_NAME, previousListPath);
            }
            deleteRecursively(tempDir);
        }

        private static void deleteRecursively(File file) throws Exception {
            if (file == null || !file.exists()) {
                return;
            }
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children != null) {
                    for (File child : children) {
                        deleteRecursively(child);
                    }
                }
            }
            Files.deleteIfExists(file.toPath());
        }
    }
}
