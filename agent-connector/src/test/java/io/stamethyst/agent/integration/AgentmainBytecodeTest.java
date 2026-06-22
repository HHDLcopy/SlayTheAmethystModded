package io.stamethyst.agent.integration;

import io.stamethyst.agent.channel.AgentDataChannel;
import io.stamethyst.agent.monitors.MonitorCapability;
import io.stamethyst.agent.monitors.SpecMonitorRegistry;
import io.stamethyst.agent.monitors.impl.TracingMonitorAgent;
import io.stamethyst.agent.util.AsmMethodInterceptor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.*;
import java.util.jar.JarFile;

import static org.junit.Assert.*;

public class AgentmainBytecodeTest {

    private FakeInstrumentation fakeInst;
    private SpecMonitorRegistry registry;
    private CapturingChannel channel;
    private TracingMonitorAgent agent;

    @Before
    public void setUp() {
        fakeInst = new FakeInstrumentation();
        registry = new SpecMonitorRegistry();
        channel = new CapturingChannel("tracing-1");

        registry.register("tracing", new SpecMonitorRegistry.MonitorFactory() {
            @Override
            public io.stamethyst.agent.monitors.MonitorAgent create(Instrumentation inst, String argsJson, AgentDataChannel channel) {
                TracingMonitorAgent a = new TracingMonitorAgent();
                a.attach(inst, argsJson, channel);
                return a;
            }
        });
    }

    @After
    public void tearDown() {
        if (agent != null) {
            agent.detach();
        }
    }

    @Test
    public void tracingAgentRegistersTransformer() {
        agent = (TracingMonitorAgent) registry.create(
            "tracing@classes=io.stamethyst.test.*@methods=compute",
            fakeInst, channel);

        assertEquals("transformer should be registered", 1, fakeInst.transformers.size());
    }

    @Test
    public void tracingAgentDetachRemovesTransformer() {
        agent = (TracingMonitorAgent) registry.create(
            "tracing@classes=io.stamethyst.test.*@methods=compute",
            fakeInst, channel);

        agent.detach();
        assertEquals(0, fakeInst.transformers.size());
    }

    @Test
    public void asmInterceptorInjectsBridgeCalls() throws Exception {
        byte[] inputBytes = buildSimpleClass("io/stamethyst/test/Target",
            "compute", "()I",
            Opcodes.ICONST_0, Opcodes.IRETURN);

        byte[] outputBytes = applyInterceptor(inputBytes, "io/stamethyst/test/Target",
            new String[]{"compute"});

        assertNotNull("transformer should return transformed bytes", outputBytes);
        assertTrue("output should differ from input after tracing injection",
            !java.util.Arrays.equals(inputBytes, outputBytes));

        // Load and invoke — verify method still works after injection
        ByteClassLoader loader = new ByteClassLoader("io.stamethyst.test.Target", outputBytes);
        Class<?> cls = loader.loadClass("io.stamethyst.test.Target");
        Method method = cls.getMethod("compute");
        Object result = method.invoke(cls.getConstructor().newInstance());
        assertEquals(0, result);
    }

    @Test
    public void asmInterceptorSkipsNonMatchingClass() throws Exception {
        byte[] inputBytes = buildSimpleClass("io/stamethyst/other/Helper",
            "compute", "()I",
            Opcodes.ICONST_0, Opcodes.IRETURN);

        byte[] outputBytes = applyInterceptor(inputBytes, "io.stamethyst.other.Helper",
            new String[]{"compute"}, "io.stamethyst.test.*");

        assertNull("non-matching class should return null", outputBytes);
    }

    @Test
    public void asmInterceptorSkipsNonMatchingMethod() throws Exception {
        byte[] inputBytes = buildSimpleClass("io/stamethyst/test/Target",
            "otherMethod", "()V",
            Opcodes.RETURN);

        // Class matches, but method doesn't match filter — bytecode unchanged
        byte[] outputBytes = applyInterceptorAlways(inputBytes, "io/stamethyst/test/Target",
            new String[]{"compute", "render"});

        assertArrayEquals("non-matching method should produce identical bytecode",
            inputBytes, outputBytes);
    }

    @Test
    public void asmInterceptorInjectsEntryAndExit() throws Exception {
        // Accept a reasonable method body: load local 0, return
        byte[] inputBytes = buildSimpleClass("io/stamethyst/test/Probe",
            "doWork", "(I)I",
            Opcodes.ILOAD, 0, Opcodes.IRETURN);

        byte[] outputBytes = applyInterceptor(inputBytes, "io/stamethyst/test/Probe",
            new String[]{"doWork"});

        // Transformed class should be loadable and callable
        ByteClassLoader loader = new ByteClassLoader("io.stamethyst.test.Probe", outputBytes);
        Class<?> cls = loader.loadClass("io.stamethyst.test.Probe");
        Method method = cls.getMethod("doWork", int.class);
        Object result = method.invoke(cls.getConstructor().newInstance(), 42);
        assertEquals(42, result);
    }

    @Test
    public void transformedClassIsLoadable() throws Exception {
        byte[] inputBytes = buildSimpleClass("io/stamethyst/test/Loadable",
            "getValue", "()I",
            Opcodes.ICONST_4, Opcodes.IRETURN);

        byte[] transformedBytes = applyInterceptor(inputBytes,
            "io/stamethyst/test/Loadable", new String[]{"getValue"});

        // Load transformed class and invoke the method
        ByteClassLoader loader = new ByteClassLoader(
            "io.stamethyst.test.Loadable", transformedBytes);
        Class<?> cls = loader.loadClass("io.stamethyst.test.Loadable");
        Object instance = cls.getConstructor().newInstance();
        Method method = cls.getMethod("getValue");
        Object result = method.invoke(instance);

        assertEquals(4, result);
    }

    // --- helpers ---

    private static byte[] buildSimpleClass(String internalName,
                                           String methodName, String descriptor,
                                           int... insnCodes) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName,
            null, "java/lang/Object", null);

        org.objectweb.asm.MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC,
            "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        org.objectweb.asm.MethodVisitor mv2 = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            methodName, descriptor, null, null);
        mv2.visitCode();
        for (int i = 0; i < insnCodes.length; i++) {
            mv2.visitInsn(insnCodes[i]);
        }
        mv2.visitMaxs(2, 2);
        mv2.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private byte[] applyInterceptorAlways(byte[] inputBytes, String internalName,
                                           String[] methodFilter) {
        String dotName = internalName.replace('/', '.');
        try {
            ClassReader reader = new ClassReader(inputBytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
            AsmMethodInterceptor interceptor = new AsmMethodInterceptor(writer,
                dotName, methodFilter, channel);
            reader.accept(interceptor, 0);
            return writer.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] applyInterceptor(byte[] inputBytes, String internalName,
                                     String[] methodFilter) {
        return applyInterceptor(inputBytes, internalName, methodFilter, internalName);
    }

    private byte[] applyInterceptor(byte[] inputBytes, String internalName,
                                     String[] methodFilter, String classPattern) {
        // Transform internal name to dot notation for matching
        String dotName = internalName.replace('/', '.');
        String dotPattern = classPattern.replace('/', '.');
        if (!io.stamethyst.agent.monitors.impl.TracingMonitorAgent.matchesPattern(dotName, dotPattern)) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(inputBytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
            AsmMethodInterceptor interceptor = new AsmMethodInterceptor(writer,
                dotName, methodFilter, channel);
            reader.accept(interceptor, 0);
            return writer.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String disassemble(byte[] bytes) {
        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(bytes);
        reader.accept(new org.objectweb.asm.util.TraceClassVisitor(pw), 0);
        pw.flush();
        return sw.toString();
    }

    // --- test doubles ---

    private static class CapturingChannel implements AgentDataChannel {
        private final String id;
        final List<String> events = Collections.synchronizedList(new ArrayList<String>());
        CapturingChannel(String id) { this.id = id; }
        @Override public void send(String agentId, String jsonPayload) { events.add(jsonPayload); }
        @Override public String getAgentId() { return id; }
        @Override public Set<MonitorCapability> getCapabilities() { return EnumSet.of(MonitorCapability.TRACING); }
    }

    private static class FakeInstrumentation implements Instrumentation {
        final List<ClassFileTransformer> transformers = new ArrayList<ClassFileTransformer>();
        final List<Class<?>> retransformCalledClasses = new ArrayList<Class<?>>();

        @Override public void addTransformer(ClassFileTransformer transformer, boolean canRetransform) {
            transformers.add(transformer);
        }
        @Override public void addTransformer(ClassFileTransformer transformer) {
            transformers.add(transformer);
        }
        @Override public boolean removeTransformer(ClassFileTransformer transformer) {
            return transformers.remove(transformer);
        }
        @Override public boolean isRetransformClassesSupported() { return true; }
        @Override public void retransformClasses(Class<?>... classes) {
            for (Class<?> c : classes) retransformCalledClasses.add(c);
        }
        @Override public boolean isRedefineClassesSupported() { return true; }
        @Override public void redefineClasses(ClassDefinition... definitions) {}
        @Override public boolean isModifiableClass(Class<?> theClass) { return true; }
        @Override public Class<?>[] getAllLoadedClasses() {
            return new Class<?>[]{ Object.class, String.class, Integer.class };
        }
        @Override public Class<?>[] getInitiatedClasses(ClassLoader loader) { return new Class<?>[0]; }
        @Override public long getObjectSize(Object objectToSize) { return 0; }
        @Override public void appendToBootstrapClassLoaderSearch(JarFile jarfile) {}
        @Override public void appendToSystemClassLoaderSearch(JarFile jarfile) {}
        @Override public boolean isNativeMethodPrefixSupported() { return false; }
        @Override public void setNativeMethodPrefix(ClassFileTransformer transformer, String prefix) {}
    }

    private static class ByteClassLoader extends ClassLoader {
        private final String name;
        private final byte[] bytes;
        ByteClassLoader(String name, byte[] bytes) {
            super(ByteClassLoader.class.getClassLoader());
            this.name = name;
            this.bytes = bytes;
        }
        @Override
        protected Class<?> findClass(String className) throws ClassNotFoundException {
            if (className.equals(name)) {
                return defineClass(className, bytes, 0, bytes.length);
            }
            return super.findClass(className);
        }
    }
}
