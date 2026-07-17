package io.stamethyst.probe.connection;

import io.stamethyst.probe.channel.AgentDataChannel;
import io.stamethyst.probe.GameProbe;
import io.stamethyst.probe.monitors.Monitor;
import io.stamethyst.probe.monitors.MonitorCapability;
import io.stamethyst.probe.monitors.MonitorRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class AgentSessionTest {

    private MonitorRegistry registry;
    private ServerSocket server;
    private Socket serverSide;
    private Socket clientSide;
    private BufferedReader reader;
    private PrintWriter writer;
    private Thread sessionThread;
    private AgentSession currentSession;
    private ClassLoader previousGameClassLoader;

    @Before
    public void setUp() throws Exception {
        previousGameClassLoader = GameProbe.GAME_CLASSLOADER;
        registry = new MonitorRegistry();
        registry.register("mock", new MonitorRegistry.MonitorFactory() {
            @Override
            public Monitor create(Instrumentation inst, String argsJson, AgentDataChannel channel) {
                return new MockMonitor("mock-instance-" + argsJson, channel);
            }
        });
        registry.register("tracing", new MonitorRegistry.MonitorFactory() {
            @Override
            public Monitor create(Instrumentation inst, String argsJson, AgentDataChannel channel) {
                return new MockMonitor("tracing-instance", channel);
            }
        });

        server = new ServerSocket(0);
        Thread acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    serverSide = server.accept();
                } catch (IOException ignored) {}
            }
        });
        acceptThread.start();

        clientSide = new Socket("127.0.0.1", server.getLocalPort());
        acceptThread.join(1000);
        assertNotNull("server failed to accept", serverSide);

        writer = new PrintWriter(clientSide.getOutputStream(), true);
    }

    @After
    public void tearDown() throws Exception {
        GameProbe.GAME_CLASSLOADER = previousGameClassLoader;
        try { clientSide.close(); } catch (Exception ignored) {}
        try { serverSide.close(); } catch (Exception ignored) {}
        try { server.close(); } catch (Exception ignored) {}
    }

    private BufferedReader startSession() throws Exception {
        currentSession = new AgentSession(serverSide, registry, null);
        sessionThread = new Thread(currentSession);
        sessionThread.start();
        return new BufferedReader(new InputStreamReader(clientSide.getInputStream()));
    }

    @Test
    public void attachReturnsAgentId() throws Exception {
        BufferedReader serverReader = startSession();
        writer.println("ATTACH mock {}");
        String response = serverReader.readLine();
        assertTrue(response, response.startsWith("OK mock-"));
    }

    @Test
    public void attachReturnsDifferentIds() throws Exception {
        BufferedReader serverReader = startSession();
        writer.println("ATTACH mock {}");
        String id1 = serverReader.readLine().substring(3);

        writer.println("ATTACH mock {}");
        String id2 = serverReader.readLine().substring(3);

        assertNotEquals(id1, id2);
    }

    @Test
    public void listShowsAttachedAgents() throws Exception {
        BufferedReader serverReader = startSession();
        writer.println("ATTACH mock {}");
        String ok1 = serverReader.readLine();
        String id1 = ok1.substring(3);

        writer.println("LIST");
        String list = serverReader.readLine();
        assertTrue(list, list.startsWith("MONITORS"));
        assertTrue(list.contains(id1));
    }

    @Test
    public void detachRemovesAgent() throws Exception {
        BufferedReader serverReader = startSession();
        writer.println("ATTACH mock {}");
        String id1 = serverReader.readLine().substring(3);

        writer.println("DETACH " + id1);
        String ok = serverReader.readLine();
        assertEquals("OK", ok);

        writer.println("LIST");
        String list = serverReader.readLine();
        assertEquals("MONITORS", list.trim());
    }

    @Test
    public void statusReportsAgentState() throws Exception {
        BufferedReader serverReader = startSession();
        writer.println("ATTACH mock {}");
        String id1 = serverReader.readLine().substring(3);

        writer.println("STATUS " + id1);
        String status = serverReader.readLine();
        assertTrue(status, status.startsWith("STATUS " + id1 + " "));
    }

    @Test
    public void subscribeDeliversData() throws Exception {
        BufferedReader serverReader = startSession();
        writer.println("ATTACH mock {}");
        String id1 = serverReader.readLine().substring(3);

        writer.println("SUBSCRIBE " + id1);
        String ok = serverReader.readLine();
        assertEquals("OK", ok);

        String testJson = "{\"type\":\"test_event\",\"value\":42}";
        currentSession.sendToSubscriber(id1, testJson);

        String dataLine = serverReader.readLine();
        assertEquals("DATA " + id1 + " " + testJson, dataLine);
    }

    @Test
    public void unsubscribeStopsDataDelivery() throws Exception {
        BufferedReader serverReader = startSession();
        writer.println("ATTACH mock {}");
        String id1 = serverReader.readLine().substring(3);

        writer.println("SUBSCRIBE " + id1);
        serverReader.readLine();

        writer.println("UNSUBSCRIBE " + id1);
        assertEquals("OK", serverReader.readLine());

        currentSession.sendToSubscriber(id1, "{\"type\":\"should_not_arrive\"}");

        writer.println("QUIT");
        assertEquals("BYE", serverReader.readLine());
    }

    @Test
    public void quitReturnsBye() throws Exception {
        BufferedReader serverReader = startSession();
        writer.println("QUIT");
        assertEquals("BYE", serverReader.readLine());
        sessionThread.join(1000);
        assertFalse(sessionThread.isAlive());
    }

    @Test
    public void unknownAgentStatus() throws Exception {
        BufferedReader serverReader = startSession();
        writer.println("STATUS nonexistent-1");
        String response = serverReader.readLine();
        assertTrue(response, response.startsWith("ERROR"));
    }

    @Test
    public void detachUnknownAgent() throws Exception {
        BufferedReader serverReader = startSession();
        writer.println("DETACH nonexistent-1");
        String response = serverReader.readLine();
        assertTrue(response, response.startsWith("ERROR"));
    }

    @Test
    public void malformedCommandReturnsError() throws Exception {
        BufferedReader serverReader = startSession();
        writer.println("GARBAGE");
        String response = serverReader.readLine();
        assertTrue(response, response.startsWith("ERROR"));
    }

    @Test
    public void consoleReturnsErrorWhenBaseModNotLoaded() throws Exception {
        BufferedReader serverReader = startSession();
        writer.println("CONSOLE gold 999");
        String response = serverReader.readLine();
        assertTrue(response, response.startsWith("RESULT "));
        assertTrue(response, response.contains("\"executed\":false"));
        assertTrue(response, response.contains("DevConsole not loaded"));
    }

    @Test
    public void consoleUsesCapturedGameClassLoader() throws Exception {
        GameProbe.GAME_CLASSLOADER = new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (!"basemod.DevConsole".equals(name)) return super.findClass(name);
                byte[] bytes = devConsoleClassBytes();
                return defineClass(name, bytes, 0, bytes.length);
            }
        };

        BufferedReader serverReader = startSession();
        writer.println("CONSOLE crossspire status");
        String response = serverReader.readLine();

        assertTrue(response, response.startsWith("RESULT "));
        assertTrue(response, response.contains("\"executed\":true"));
        assertTrue(response, response.contains("crossspire status"));
    }

    private static byte[] devConsoleClassBytes() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "basemod/DevConsole", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "execute",
            "(Ljava/lang/String;)Ljava/lang/String;",
            null,
            null
        );
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    public void multipleAgentsSimultaneously() throws Exception {
        BufferedReader serverReader = startSession();

        writer.println("ATTACH mock {}");
        String id1 = serverReader.readLine().substring(3);
        writer.println("ATTACH tracing {}");
        String id2 = serverReader.readLine().substring(3);
        writer.println("ATTACH mock {}");
        String id3 = serverReader.readLine().substring(3);

        writer.println("LIST");
        String list = serverReader.readLine();
        assertTrue(list.contains(id1));
        assertTrue(list.contains(id2));
        assertTrue(list.contains(id3));
        assertNotEquals(id1, id2);
        assertNotEquals(id1, id3);
    }

    private static class MockMonitor implements Monitor {
        private final String status;
        private final AgentDataChannel channel;
        private volatile boolean attached = true;
        MockMonitor(String status, AgentDataChannel channel) {
            this.status = status;
            this.channel = channel;
        }
        @Override public void attach(Instrumentation inst, String agentArgs, AgentDataChannel channel) {}
        @Override public void detach() { attached = false; }
        @Override public String status() { return attached ? status : "detached"; }
        @Override public Set<MonitorCapability> capabilities() { return EnumSet.of(MonitorCapability.TRACING); }
    }
}
