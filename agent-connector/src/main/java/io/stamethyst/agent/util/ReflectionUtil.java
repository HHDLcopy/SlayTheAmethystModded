package io.stamethyst.agent.util;

import io.stamethyst.agent.AgentConnector;

import java.lang.reflect.Field;

/**
 * Reflection helpers that bridge the agent's system ClassLoader to the
 * MTS/game URLClassLoader captured during premain.
 */
public final class ReflectionUtil {

    private static final boolean DIAGNOSTIC = true;

    private ReflectionUtil() {}

    /** Find a loaded class by name across ALL classloaders. */
    public static Class<?> forName(String fqcn) {
        if (fqcn == null || fqcn.isEmpty()) return null;

        // 1. Try the captured game ClassLoader first
        ClassLoader gameCl = AgentConnector.GAME_CLASSLOADER;
        if (gameCl != null) {
            try { return Class.forName(fqcn, false, gameCl); }
            catch (ClassNotFoundException ignored) {}
        }

        // 2. Try system ClassLoader
        try { return Class.forName(fqcn); }
        catch (ClassNotFoundException ignored) {}

        // 3. Walk all loaded classes to steal the right ClassLoader
        java.lang.instrument.Instrumentation inst = AgentConnector.getInstrumentation();
        if (inst != null) {
            for (Class<?> cls : inst.getAllLoadedClasses()) {
                if (cls.getName().equals(fqcn)) return cls;
            }
            // Try any game class to find the loader
            for (Class<?> cls : inst.getAllLoadedClasses()) {
                if (isGameClass(cls.getName())) {
                    try {
                        Class<?> found = Class.forName(fqcn, false, cls.getClassLoader());
                        AgentConnector.GAME_CLASSLOADER = cls.getClassLoader();
                        return found;
                    } catch (ClassNotFoundException ignored) {}
                    break;
                }
            }
        }

        if (DIAGNOSTIC) {
            System.err.println("[ReflectionUtil] forName FAILED all branches for: " + fqcn
                + " (GAME_CLASSLOADER=" + (gameCl != null ? gameCl.getClass().getName() : "null")
                + ", inst=" + (inst != null) + ")");
        }
        return null;
    }

    private static boolean isGameClass(String name) {
        return name.startsWith("com.megacrit.cardcrawl.")
            || name.startsWith("com.badlogic.gdx.")
            || name.startsWith("io.stamethyst.compatmod.")
            || name.startsWith("io.stamethyst.testcrash.");
    }

    /**
     * Read a static field value, searching first through the game ClassLoader.
     */
    public static Object getStaticField(String fqcn, String fieldName) {
        Class<?> cls = forName(fqcn);
        if (cls == null) {
            if (DIAGNOSTIC) {
                System.err.println("[ReflectionUtil] getStaticField class not found: " + fqcn + "." + fieldName);
            }
            return null;
        }
        try {
            return cls.getField(fieldName).get(null);
        } catch (NoSuchFieldException e) {
            if (DIAGNOSTIC) {
                System.err.println("[ReflectionUtil] getStaticField field not found: " + fqcn + "." + fieldName);
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Invoke a static method, searching first through the game ClassLoader.
     */
    public static Object invokeStatic(String fqcn, String methodName) {
        Class<?> cls = forName(fqcn);
        if (cls == null) return null;
        try {
            return cls.getMethod(methodName).invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Read a field from an object instance.
     */
    public static Object getField(Object obj, String name) {
        if (obj == null) return null;
        try {
            return obj.getClass().getField(name).get(obj);
        } catch (Throwable t) {
            return null;
        }
    }
}
