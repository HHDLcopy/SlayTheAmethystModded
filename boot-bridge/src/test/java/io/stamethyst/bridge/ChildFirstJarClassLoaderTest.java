package io.stamethyst.bridge;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ChildFirstJarClassLoaderTest {

    @Test
    public void parentFirstNamespace_loadsFromParentWhenPresent() throws Exception {
        File root = Files.createTempDirectory("classloader-test-").toFile();
        try {
            File parentJar = buildJarWithClass(root, "parent.jar", "com.badlogic.gdx.ParentClass");
            URLClassLoader parentLoader = new URLClassLoader(
                    new URL[]{parentJar.toURI().toURL()},
                    ChildFirstJarClassLoaderTest.class.getClassLoader()
            );

            File childJar = buildJarWithClass(root, "child.jar", "com.example.ChildClass");
            MtsPatchCacheBootstrap.ChildFirstJarClassLoader childLoader =
                    new MtsPatchCacheBootstrap.ChildFirstJarClassLoader(
                            new URL[]{childJar.toURI().toURL()},
                            parentLoader
                    );

            Class<?> parentClass = childLoader.loadClass("com.badlogic.gdx.ParentClass");
            Class<?> parentClassDirect = parentLoader.loadClass("com.badlogic.gdx.ParentClass");

            assertSame("Reserved namespace class must have same identity as parent", 
                    parentClassDirect, parentClass);
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void parentFirstNamespace_fallsBackToChildWhenNotInParent() throws Exception {
        File root = Files.createTempDirectory("classloader-test-").toFile();
        try {
            URLClassLoader parentLoader = new URLClassLoader(
                    new URL[0],
                    ChildFirstJarClassLoaderTest.class.getClassLoader()
            );

            File childJar = buildJarWithClass(root, "child.jar", "io.stamethyst.bridge.ChildOnlyClass");
            MtsPatchCacheBootstrap.ChildFirstJarClassLoader childLoader =
                    new MtsPatchCacheBootstrap.ChildFirstJarClassLoader(
                            new URL[]{childJar.toURI().toURL()},
                            parentLoader
                    );

            Class<?> childClass = childLoader.loadClass("io.stamethyst.bridge.ChildOnlyClass");
            assertNotNull("Reserved namespace class in child-only should still load", childClass);
            assertEquals("io.stamethyst.bridge.ChildOnlyClass", childClass.getName());
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void childFirstNamespace_prefersChildOverParent() throws Exception {
        File root = Files.createTempDirectory("classloader-test-").toFile();
        try {
            File parentJar = buildJarWithClass(root, "parent.jar", "com.example.DuplicateClass");
            URLClassLoader parentLoader = new URLClassLoader(
                    new URL[]{parentJar.toURI().toURL()},
                    ChildFirstJarClassLoaderTest.class.getClassLoader()
            );

            File childJar = buildJarWithClass(root, "child.jar", "com.example.DuplicateClass");
            MtsPatchCacheBootstrap.ChildFirstJarClassLoader childLoader =
                    new MtsPatchCacheBootstrap.ChildFirstJarClassLoader(
                            new URL[]{childJar.toURI().toURL()},
                            parentLoader
                    );

            Class<?> childClass = childLoader.loadClass("com.example.DuplicateClass");
            Class<?> parentClass = parentLoader.loadClass("com.example.DuplicateClass");

            assertNotSame("Non-reserved namespace must prefer child copy", parentClass, childClass);
            assertSame("Child class must come from child loader", childLoader, childClass.getClassLoader());
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void packageBoundary_doesNotMatchUnrelatedClass() throws Exception {
        File root = Files.createTempDirectory("classloader-test-").toFile();
        try {
            File childJar = buildJarWithClass(root, "child.jar", "javafx.NotReserved");
            MtsPatchCacheBootstrap.ChildFirstJarClassLoader childLoader =
                    new MtsPatchCacheBootstrap.ChildFirstJarClassLoader(
                            new URL[]{childJar.toURI().toURL()},
                            ChildFirstJarClassLoaderTest.class.getClassLoader()
                    );

            Class<?> cls = childLoader.loadClass("javafx.NotReserved");
            assertNotNull("Class with reserved-like prefix but non-reserved package should load", cls);
            assertSame("Non-reserved class should load from child", childLoader, cls.getClassLoader());
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void resourceLookup_prefersChildResources() throws Exception {
        File root = Files.createTempDirectory("classloader-test-").toFile();
        try {
            File parentJar = buildJarWithResource(root, "parent.jar", "test.txt", "parent content");
            URLClassLoader parentLoader = new URLClassLoader(
                    new URL[]{parentJar.toURI().toURL()},
                    ChildFirstJarClassLoaderTest.class.getClassLoader()
            );

            File childJar = buildJarWithResource(root, "child.jar", "test.txt", "child content");
            MtsPatchCacheBootstrap.ChildFirstJarClassLoader childLoader =
                    new MtsPatchCacheBootstrap.ChildFirstJarClassLoader(
                            new URL[]{childJar.toURI().toURL()},
                            parentLoader
                    );

            URL resource = childLoader.getResource("test.txt");
            assertNotNull("Resource should be found", resource);
            assertEquals("Child resource must shadow parent", "child content", readAll(resource));
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void getResources_ordersChildBeforeParent() throws Exception {
        File root = Files.createTempDirectory("classloader-test-").toFile();
        try {
            File parentJar = buildJarWithResource(root, "parent.jar", "multi.txt", "parent");
            URLClassLoader parentLoader = new URLClassLoader(
                    new URL[]{parentJar.toURI().toURL()},
                    ChildFirstJarClassLoaderTest.class.getClassLoader()
            );

            File childJar = buildJarWithResource(root, "child.jar", "multi.txt", "child");
            MtsPatchCacheBootstrap.ChildFirstJarClassLoader childLoader =
                    new MtsPatchCacheBootstrap.ChildFirstJarClassLoader(
                            new URL[]{childJar.toURI().toURL()},
                            parentLoader
                    );

            Enumeration<URL> resources = childLoader.getResources("multi.txt");
            assertTrue("At least one resource must be found", resources.hasMoreElements());

            assertEquals("First resource must be from child", "child", readAll(resources.nextElement()));
        } finally {
            deleteRecursively(root);
        }
    }

    private static String readAll(URL url) throws Exception {
        java.io.InputStream input = url.openStream();
        try {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = input.read(chunk)) != -1) {
                buffer.write(chunk, 0, count);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            input.close();
        }
    }

    private File buildJarWithClass(File root, String jarName, String className) throws Exception {
        File sourceDir = new File(root, "src-" + jarName);
        File classDir = new File(root, "classes-" + jarName);
        assertTrue(sourceDir.mkdirs());
        assertTrue(classDir.mkdirs());

        String packageName = className.substring(0, className.lastIndexOf('.'));
        String simpleName = className.substring(className.lastIndexOf('.') + 1);
        File packageDir = new File(sourceDir, packageName.replace('.', '/'));
        assertTrue(packageDir.mkdirs());

        File sourceFile = new File(packageDir, simpleName + ".java");
        Files.write(
                sourceFile.toPath(),
                ("package " + packageName + ";\n" +
                        "public class " + simpleName + " {\n" +
                        "  public static String identify() { return \"" + jarName + "\"; }\n" +
                        "}\n").getBytes(StandardCharsets.UTF_8)
        );

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK compiler required for test");
        }
        int result = compiler.run(null, null, null, "-d", classDir.getAbsolutePath(), sourceFile.getAbsolutePath());
        assertEquals("Compilation must succeed", 0, result);

        File jar = new File(root, jarName);
        JarOutputStream jarOut = new JarOutputStream(new FileOutputStream(jar));
        try {
            File classFile = new File(classDir, className.replace('.', '/') + ".class");
            jarOut.putNextEntry(new JarEntry(className.replace('.', '/') + ".class"));
            Files.copy(classFile.toPath(), jarOut);
            jarOut.closeEntry();
        } finally {
            jarOut.close();
        }
        return jar;
    }

    private File buildJarWithResource(File root, String jarName, String resourcePath, String content) throws Exception {
        File jar = new File(root, jarName);
        JarOutputStream jarOut = new JarOutputStream(new FileOutputStream(jar));
        try {
            jarOut.putNextEntry(new JarEntry(resourcePath));
            jarOut.write(content.getBytes(StandardCharsets.UTF_8));
            jarOut.closeEntry();
        } finally {
            jarOut.close();
        }
        return jar;
    }

    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }
}
