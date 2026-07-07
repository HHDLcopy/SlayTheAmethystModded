package io.stamethyst.arthas;

import java.lang.instrument.Instrumentation;

public class CommonSuperBridge {

    private static volatile Instrumentation instrumentation;

    public static void setInstrumentation(Instrumentation inst) {
        instrumentation = inst;
    }

    public static String resolveCommonSuper(String type1, String type2) {
        if (instrumentation == null) {
            return null;
        }
        Class<?> c1 = findClass(type1.replace('/', '.'));
        Class<?> c2 = findClass(type2.replace('/', '.'));
        if (c1 == null || c2 == null) {
            return null;
        }
        if (c1.isAssignableFrom(c2)) return type1;
        if (c2.isAssignableFrom(c1)) return type2;
        if (c1.isInterface() || c2.isInterface()) return "java/lang/Object";
        for (Class<?> sup = c1.getSuperclass(); sup != null; sup = sup.getSuperclass()) {
            if (sup.isAssignableFrom(c2)) {
                return sup.getName().replace('.', '/');
            }
        }
        return "java/lang/Object";
    }

    private static Class<?> findClass(String name) {
        try {
            return Class.forName(name, false, CommonSuperBridge.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            for (Class<?> c : instrumentation.getAllLoadedClasses()) {
                if (c.getName().equals(name)) {
                    return c;
                }
            }
            return null;
        }
    }
}
