package io.stamethyst.agent.monitors;

import io.stamethyst.agent.channel.AgentDataChannel;
import io.stamethyst.agent.monitors.impl.BytecodeDumpAgent;
import org.junit.Test;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.jar.JarFile;

import static org.junit.Assert.*;

public class BytecodeDumpAgentTest {

    @Test
    public void capabilities() {
        BytecodeDumpAgent agent = new BytecodeDumpAgent();
        Set<MonitorCapability> caps = agent.capabilities();
        assertEquals(1, caps.size());
        assertTrue(caps.contains(MonitorCapability.BYTECODE));
    }

    @Test
    public void attachAndDetach() {
        CapturingChannel channel = new CapturingChannel("dump-test");
        BytecodeDumpAgent agent = new BytecodeDumpAgent();
        agent.attach(null, "{\"classes\":\"java.lang.String\"}", channel);
        assertEquals("active", agent.status());
        agent.detach();
        assertEquals("detached", agent.status());
    }

    @Test
    public void attachWithEmptyClasses() {
        CapturingChannel channel = new CapturingChannel("dump-test");
        BytecodeDumpAgent agent = new BytecodeDumpAgent();
        agent.attach(null, "{}", channel);
        assertNotNull(agent.status());
    }

    @Test
    public void retransformsOnlyMatchingClasses() {
        CapturingChannel channel = new CapturingChannel("dump-test");
        BytecodeDumpAgent agent = new BytecodeDumpAgent();
        StoringInstrumentation inst = new StoringInstrumentation(
            new Class<?>[]{String.class, Integer.class, HashMap.class},
            new byte[][]{new byte[]{1, 2}, new byte[]{3, 4}, new byte[]{5, 6}}
        );
        inst.retransformSupported = true;

        agent.attach(inst, "{\"classes\":\"java.lang.String,java.util.HashMap\"}", channel);

        assertEquals("should retransform 2 matching classes", 2, inst.retransformedNames.size());
        assertTrue(inst.retransformedNames.contains("java.lang.String"));
        assertTrue(inst.retransformedNames.contains("java.util.HashMap"));
        assertFalse(inst.retransformedNames.contains("java.lang.Integer"));
    }

    @Test
    public void transformerSendsBase64EncodedBytes() {
        CapturingChannel channel = new CapturingChannel("dump-test");
        BytecodeDumpAgent agent = new BytecodeDumpAgent();

        byte[] sample = new byte[256];
        for (int i = 0; i < 256; i++) sample[i] = (byte) i;

        ClassFileTransformer t = BytecodeDumpAgent.createDumpTransformer(channel);
        try {
            t.transform(
                BytecodeDumpAgentTest.class.getClassLoader(),
                "com/example/Data",
                BytecodeDumpAgentTest.class,
                BytecodeDumpAgentTest.class.getProtectionDomain(),
                sample
            );
        } catch (IllegalClassFormatException e) {
            fail("unexpected IllegalClassFormatException: " + e.getMessage());
        }

        assertEquals(1, channel.events.size());
        String json = channel.events.get(0);
        assertTrue(json.contains("\"class\":\"com.example.Data\""));
        assertTrue(json.contains("\"bytecode\""));

        String b64 = extractField(json, "bytecode");
        byte[] decoded = Base64.getDecoder().decode(b64);
        assertArrayEquals(sample, decoded);
    }

    @Test
    public void transformerReturnsNullForNoModification() {
        CapturingChannel channel = new CapturingChannel("dump-test");
        ClassFileTransformer t = BytecodeDumpAgent.createDumpTransformer(channel);
        byte[] input = new byte[]{0xa, 0xb, 0xc};
        byte[] result;
        try {
            result = t.transform(
                BytecodeDumpAgentTest.class.getClassLoader(),
                "com/example/X",
                BytecodeDumpAgentTest.class,
                BytecodeDumpAgentTest.class.getProtectionDomain(),
                input
            );
        } catch (IllegalClassFormatException e) {
            fail("unexpected IllegalClassFormatException: " + e.getMessage());
            return;
        }
        assertNull("transformer must return null to not modify class bytes", result);
    }

    @Test
    public void detachRemovesTransformer() {
        CapturingChannel channel = new CapturingChannel("dump-test");
        BytecodeDumpAgent agent = new BytecodeDumpAgent();
        StoringInstrumentation inst = new StoringInstrumentation(
            new Class<?>[]{String.class},
            new byte[][]{new byte[]{1}}
        );
        inst.retransformSupported = true;

        agent.attach(inst, "{\"classes\":\"java.lang.String\"}", channel);
        assertFalse("should have registered a transformer", inst.transformers.isEmpty());

        agent.detach();
        assertTrue("should have removed transformers", inst.transformers.isEmpty());
    }

    private static String extractField(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf('"', start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    private static class CapturingChannel implements AgentDataChannel {
        private final String id;
        final List<String> events = new ArrayList<>();

        CapturingChannel(String id) { this.id = id; }

        @Override public void send(String agentId, String jsonPayload) { events.add(jsonPayload); }
        @Override public String getAgentId() { return id; }
        @Override public Set<MonitorCapability> getCapabilities() { return EnumSet.noneOf(MonitorCapability.class); }
    }

    static class StoringInstrumentation implements Instrumentation {
        final List<ClassFileTransformer> transformers = new ArrayList<>();
        final Class<?>[] loadedClasses;
        final byte[][] loadedBytes;
        final List<String> retransformedNames = new ArrayList<>();
        boolean retransformSupported;

        StoringInstrumentation(Class<?>[] loadedClasses, byte[][] loadedBytes) {
            this.loadedClasses = loadedClasses;
            this.loadedBytes = loadedBytes;
        }

        @Override
        public void addTransformer(ClassFileTransformer t, boolean cr) { transformers.add(t); }
        @Override public void addTransformer(ClassFileTransformer t) { transformers.add(t); }
        @Override public boolean removeTransformer(ClassFileTransformer t) { return transformers.remove(t); }
        @Override public boolean isRetransformClassesSupported() { return retransformSupported; }

        @Override
        public void retransformClasses(Class<?>... classes) {
            for (Class<?> cls : classes) {
                retransformedNames.add(cls.getName());
                // Find matching byte array
                byte[] current = null;
                for (int i = 0; i < loadedClasses.length; i++) {
                    if (loadedClasses[i] == cls) {
                        current = loadedBytes[i];
                        break;
                    }
                }
                if (current == null) continue;
                for (int i = transformers.size() - 1; i >= 0; i--) {
                    try {
                        byte[] r = transformers.get(i).transform(
                            cls.getClassLoader(), cls.getName().replace('.', '/'), cls,
                            cls.getProtectionDomain(), current);
                        if (r != null) current = r;
                    } catch (Exception e) { /* ignore */ }
                }
            }
        }

        @Override public boolean isRedefineClassesSupported() { return false; }
        @Override public void redefineClasses(ClassDefinition... d) {}
        @Override public boolean isModifiableClass(Class<?> c) { return true; }
        @Override public Class<?>[] getAllLoadedClasses() { return loadedClasses.clone(); }
        @Override public Class<?>[] getInitiatedClasses(ClassLoader l) { return new Class<?>[0]; }
        @Override public long getObjectSize(Object o) { return 0; }
        @Override public void appendToBootstrapClassLoaderSearch(JarFile j) {}
        @Override public void appendToSystemClassLoaderSearch(JarFile j) {}
        @Override public boolean isNativeMethodPrefixSupported() { return false; }
        @Override public void setNativeMethodPrefix(ClassFileTransformer t, String p) {}
    }
}
