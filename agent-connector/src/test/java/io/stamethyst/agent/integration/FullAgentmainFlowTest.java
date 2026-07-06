package io.stamethyst.agent.integration;

import io.stamethyst.agent.AgentConnector;
import io.stamethyst.agent.channel.AgentDataChannel;
import io.stamethyst.agent.connection.AgentConnectionManager;
import io.stamethyst.agent.monitors.Monitor;
import io.stamethyst.agent.monitors.MonitorRegistry;
import io.stamethyst.agent.monitors.impl.TracingMonitor;
import org.junit.*;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.*;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarFile;

import static org.junit.Assert.*;

public class FullAgentmainFlowTest {

    private AgentConnectionManager manager;
    private FakeInstrumentation fakeInst;
    private int serverPort;

    @Before
    public void setUp() throws Exception {
        fakeInst = new FakeInstrumentation();
        MonitorRegistry registry = AgentConnector.getRegistry();
        if (registry == null) {
            registry = new MonitorRegistry();
            specInit(registry);
        }
        manager = new AgentConnectionManager(registry, fakeInst, 0);
        manager.start();
        serverPort = manager.getPort();
        assertTrue("server should be running", manager.isRunning());
    }

    @After
    public void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    public void fullFlow_attachDetach() throws Exception {
        TCPClient client = connect();
        String agentId = client.sendAttach("tracing@classes=com.example.*");
        assertTrue(agentId.startsWith("tracing-"));

        assertEquals(1, fakeInst.transformers.size());

        String list = client.send("LIST");
        assertTrue(list.contains(agentId));

        String status = client.send("STATUS " + agentId);
        assertTrue(status.startsWith("STATUS " + agentId + " active"));

        client.sendAndExpect("DETACH " + agentId, "OK");
        assertEquals(0, fakeInst.transformers.size());

        client.sendAndExpect("QUIT", "BYE");
        client.close();
    }

    @Test
    public void fullFlow_attachSubscribeData() throws Exception {
        TCPClient client = connect();

        String agentId = client.sendAttach("tracing@classes=com.example.Target@methods=compute");
        assertTrue(agentId.startsWith("tracing-"));

        client.sendAndExpect("SUBSCRIBE " + agentId, "OK");
        assertEquals(1, fakeInst.transformers.size());

        byte[] classBytes = buildTestClass("com/example/Target", "compute", "()I");
        ClassFileTransformer tf = fakeInst.transformers.get(0);
        byte[] transformed = tf.transform(null, "com/example/Target", null, null, classBytes);
        assertNotNull(transformed);
        assertFalse(Arrays.equals(classBytes, transformed));

        ByteClassLoader loader = new ByteClassLoader("com.example.Target", transformed);
        Class<?> cls = loader.loadClass("com.example.Target");
        Method method = cls.getMethod("compute");
        Object result = method.invoke(cls.getConstructor().newInstance());
        assertEquals(0, result);

        String line = client.readLine(2000);
        assertNotNull("should receive DATA line", line);
        assertTrue(line.contains("method_entry"));

        line = client.readLine(2000);
        assertNotNull("should receive DATA line for exit", line);
        assertTrue(line.contains("method_exit"));

        client.sendAndExpect("UNSUBSCRIBE " + agentId, "OK");
        client.sendAndExpect("DETACH " + agentId, "OK");
        client.sendAndExpect("QUIT", "BYE");
        client.close();
    }

    @Test
    public void fullFlow_multipleAgents() throws Exception {
        TCPClient client = connect();

        String id1 = client.sendAttach("tracing@classes=com.example.A");
        String id2 = client.sendAttach("tracing@classes=com.example.B");
        String id3 = client.sendAttach("state");

        String list = client.send("LIST");
        assertTrue(list.contains(id1));
        assertTrue(list.contains(id2));
        assertTrue(list.contains(id3));

        String s1 = client.send("STATUS " + id1);
        String s2 = client.send("STATUS " + id2);
        String s3 = client.send("STATUS " + id3);
        assertTrue(s1.contains("active"));
        assertTrue(s2.contains("active"));
        assertTrue(s3.contains("active"));

        client.sendAndExpect("DETACH " + id2, "OK");
        list = client.send("LIST");
        assertFalse(list.contains(id2));
        assertTrue(list.contains(id1));
        assertTrue(list.contains(id3));

        client.sendAndExpect("QUIT", "BYE");
        client.close();
    }

    @Test
    public void fullFlow_errorHandling() throws Exception {
        TCPClient client = connect();

        String resp = client.send("ATTACH unknown");
        assertTrue(resp.startsWith("ERROR"));

        resp = client.send("STATUS nonexistent");
        assertTrue(resp.startsWith("ERROR"));

        resp = client.send("DETACH nonexistent");
        assertTrue(resp.startsWith("ERROR"));

        resp = client.send("SUBSCRIBE nonexistent");
        assertTrue(resp.startsWith("ERROR"));

        client.sendAndExpect("QUIT", "BYE");
        client.close();
    }

    @Test
    public void fullFlow_statusReportAfterEvents() throws Exception {
        TCPClient client = connect();

        String agentId = client.sendAttach("tracing@classes=com.example.StatusProbe@methods=run");
        client.sendAndExpect("SUBSCRIBE " + agentId, "OK");

        byte[] classBytes = buildTestClass("com/example/StatusProbe", "run", "()V");
        FakeInstrumentation adapter = fakeInst;
        ClassFileTransformer tf = adapter.transformers.get(0);
        byte[] transformed = tf.transform(null, "com/example/StatusProbe", null, null, classBytes);

        ByteClassLoader loader = new ByteClassLoader("com.example.StatusProbe", transformed);
        Class<?> cls = loader.loadClass("com.example.StatusProbe");
        Method method = cls.getMethod("run");
        method.invoke(cls.getConstructor().newInstance());

        String entryLine = client.readLine(2000);
        String exitLine = client.readLine(2000);
        assertNotNull(entryLine);
        assertNotNull(exitLine);

        String status = client.send("STATUS " + agentId);
        assertTrue(status.contains(agentId));
        assertTrue(status.matches(".*\\d+$"));

        client.sendAndExpect("QUIT", "BYE");
        client.close();
    }

    @Test
    public void fullFlow_transformerRemovedOnDetach() throws Exception {
        TCPClient client = connect();

        String id1 = client.sendAttach("tracing@classes=com.example.X");
        assertEquals(1, fakeInst.transformers.size());

        String id2 = client.sendAttach("tracing@classes=com.example.Y");
        assertEquals(2, fakeInst.transformers.size());

        client.sendAndExpect("DETACH " + id1, "OK");
        assertEquals(1, fakeInst.transformers.size());

        client.sendAndExpect("DETACH " + id2, "OK");
        assertEquals(0, fakeInst.transformers.size());

        client.sendAndExpect("QUIT", "BYE");
        client.close();
    }

    @Test
    public void fullFlow_unsubscribeStopsData() throws Exception {
        TCPClient client = connect();

        String agentId = client.sendAttach("tracing@classes=com.example.StopData@methods=test");
        client.sendAndExpect("SUBSCRIBE " + agentId, "OK");
        client.sendAndExpect("UNSUBSCRIBE " + agentId, "OK");
        assertEquals(1, fakeInst.transformers.size());

        byte[] classBytes = buildTestClass("com/example/StopData", "test", "()I");
        ClassFileTransformer tf = fakeInst.transformers.get(0);
        byte[] transformed = tf.transform(null, "com/example/StopData", null, null, classBytes);
        assertNotNull(transformed);

        ByteClassLoader loader = new ByteClassLoader("com.example.StopData", transformed);
        Class<?> cls = loader.loadClass("com.example.StopData");
        Method method = cls.getMethod("test");
        method.invoke(cls.getConstructor().newInstance());

        // Unsubscribing should not crash the server or break the connection
        client.sendAndExpect("DETACH " + agentId, "OK");
        assertEquals(0, fakeInst.transformers.size());

        client.sendAndExpect("QUIT", "BYE");
        client.close();
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    private TCPClient connect() throws Exception {
        return new TCPClient("127.0.0.1", serverPort);
    }

    private static byte[] buildTestClass(String internalName, String methodName, String descriptor) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        org.objectweb.asm.MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC,
            "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        boolean isVoid = descriptor.endsWith("V");
        org.objectweb.asm.MethodVisitor mv2 = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName,
            descriptor, null, null);
        mv2.visitCode();
        if (isVoid) {
            mv2.visitInsn(Opcodes.RETURN);
        } else {
            mv2.visitInsn(Opcodes.ICONST_0);
            mv2.visitInsn(Opcodes.IRETURN);
        }
        mv2.visitMaxs(1, 1);
        mv2.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void specInit(MonitorRegistry registry) {
        registry.register("tracing", new MonitorRegistry.MonitorFactory() {
            @Override
            public Monitor create(Instrumentation inst, String argsJson, AgentDataChannel channel) {
                return new TracingMonitor();
            }
        });
        registry.register("tracing", new MonitorRegistry.MonitorFactory() {
            @Override
            public Monitor create(Instrumentation inst, String argsJson, AgentDataChannel channel) {
                return new io.stamethyst.agent.monitors.impl.TracingMonitor();
            }
        });
    }

    // ---------------------------------------------------------------
    // TCP client (simulates agent_bridge.py)
    // ---------------------------------------------------------------

    private static class TCPClient {
        private final Socket socket;
        private final BufferedReader reader;
        private final PrintWriter writer;

        TCPClient(String host, int port) throws IOException {
            socket = new Socket(host, port);
            socket.setSoTimeout(5000);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new PrintWriter(socket.getOutputStream(), true);
        }

        void close() {
            try { reader.close(); } catch (Exception ignored) {}
            try { writer.close(); } catch (Exception ignored) {}
            try { socket.close(); } catch (Exception ignored) {}
        }

        String send(String command) throws IOException {
            writer.println(command);
            return reader.readLine().trim();
        }

        void sendAndExpect(String command, String expected) throws IOException {
            String resp = send(command);
            assertEquals("command '" + command + "'", expected, resp.split(" ")[0]);
        }

        String sendAttach(String spec) throws IOException {
            String resp = send("ATTACH " + spec + " {}");
            assertTrue(resp, resp.startsWith("OK "));
            return resp.substring(3);
        }

        String readLine(int timeoutMs) throws IOException {
            socket.setSoTimeout(timeoutMs);
            return reader.readLine();
        }

        String tryReadLine() {
            try {
                socket.setSoTimeout(500);
                String line = reader.readLine();
                return line == null ? null : line.trim();
            } catch (Exception e) {
                return null;
            }
        }
    }

    // ---------------------------------------------------------------
    // test doubles
    // ---------------------------------------------------------------

    private static class FakeInstrumentation implements Instrumentation {
        final List<ClassFileTransformer> transformers = new CopyOnWriteArrayList<ClassFileTransformer>();

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
        @Override public void retransformClasses(Class<?>... classes) {}
        @Override public boolean isRedefineClassesSupported() { return false; }
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
