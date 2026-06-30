package com.evacipated.cardcrawl.modthespire;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public final class PackageJar {
    public static boolean writePackageJarFiles = true;
    public static boolean observedOutJarWasNull = false;
    public static int observedOutJarSize = -1;
    public static boolean observedPackageFlag = false;

    private PackageJar() {
    }

    public static void resetTracking() {
        observedOutJarWasNull = false;
        observedOutJarSize = -1;
        observedPackageFlag = false;
    }

    public static void packageJar(MTSClassPool classPool, String outputPath) throws Exception {
        Set<String> outJarClasses = classPool.getOutJarClasses();
        observedOutJarWasNull = outJarClasses == null;
        observedOutJarSize = outJarClasses == null ? -1 : outJarClasses.size();
        observedPackageFlag = Loader.PACKAGE;
        JarOutputStream output = new JarOutputStream(new FileOutputStream(outputPath));
        try {
            output.putNextEntry(new JarEntry("com/example/Patched.class"));
            output.write("patched".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();

            output.putNextEntry(new JarEntry("amethyst-cache-padding.bin"));
            byte[] padding = new byte[1024 * 1024];
            new Random(42L).nextBytes(padding);
            output.write(padding);
            output.closeEntry();
        } finally {
            output.close();
        }

        if (writePackageJarFiles) {
            File packageDir = new File(System.getProperty("amethyst.mts.patch_cache.package_dir"));
            if (!packageDir.isDirectory() && !packageDir.mkdirs()) {
                throw new IllegalStateException("Failed to create package dir");
            }
            JarOutputStream packageOutput = new JarOutputStream(
                    new FileOutputStream(new File(packageDir, "Example's Mod-modded.jar"))
            );
            try {
                packageOutput.putNextEntry(new JarEntry("example/ExampleMod.class"));
                packageOutput.write("mod".getBytes(StandardCharsets.UTF_8));
                packageOutput.closeEntry();
            } finally {
                packageOutput.close();
            }
        }
    }
}
