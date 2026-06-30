package com.evacipated.cardcrawl.modthespire;

import java.util.LinkedHashSet;
import java.util.Set;

public final class MTSClassPool {
    public static int getModifiedClassesCalls = 0;
    public static int getOutJarClassesCalls = 0;
    public static boolean detachAfterCompileSnapshot = false;

    private Set<String> outJar;
    private boolean detached = false;

    public static void resetTracking() {
        getModifiedClassesCalls = 0;
        getOutJarClassesCalls = 0;
        detachAfterCompileSnapshot = false;
    }

    public Set<String> getModifiedClasses() {
        getModifiedClassesCalls++;
        LinkedHashSet<String> modified = new LinkedHashSet<String>();
        if (!detached) {
            modified.add("com.example.Patched");
        }
        if (Loader.OUT_JAR || Loader.PACKAGE) {
            outJar = new LinkedHashSet<String>(modified);
        }
        if (detachAfterCompileSnapshot) {
            detached = true;
        }
        return modified;
    }

    public Set<String> getOutJarClasses() {
        getOutJarClassesCalls++;
        return outJar;
    }
}
