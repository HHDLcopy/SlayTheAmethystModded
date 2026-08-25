package com.evacipated.cardcrawl.modthespire;

import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Test stand-in for ModTheSpire's Patcher statics. Tests seed
 * {@link #annotationDBMap} with per-jar databases whose
 * {@code getClassAnnotationIndex()} reports the annotation types found in that jar;
 * the store's reuse planner consults it to decide which mods carry patch classes.
 */
public final class Patcher {
    public static final Map<URL, Object> annotationDBMap = new LinkedHashMap<URL, Object>();

    private Patcher() {
    }

    public static void reset() {
        annotationDBMap.clear();
    }

    public static final class StubAnnotationDB {
        private final Map<String, Collection<String>> classAnnotationIndex;

        public StubAnnotationDB(Map<String, Collection<String>> classAnnotationIndex) {
            this.classAnnotationIndex = classAnnotationIndex;
        }

        public Map<String, Collection<String>> getClassAnnotationIndex() {
            return classAnnotationIndex;
        }

        public static StubAnnotationDB withNoAnnotations() {
            return new StubAnnotationDB(Collections.<String, Collection<String>>emptyMap());
        }

        public static StubAnnotationDB withSpirePatchOn(String className) {
            Map<String, Collection<String>> index = new LinkedHashMap<String, Collection<String>>();
            index.put(
                    className,
                    Collections.singletonList("com.evacipated.cardcrawl.modthespire.lib.SpirePatch2")
            );
            return new StubAnnotationDB(index);
        }
    }
}
