package io.stamethyst.agent.monitors;

import io.stamethyst.agent.channel.AgentDataChannel;
import io.stamethyst.agent.monitors.impl.ClassMonitorAgent;
import org.junit.Test;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.EnumSet;
import java.util.Set;
import java.util.jar.JarFile;

import static org.junit.Assert.*;

public class ClassMonitorAgentTest {

    @Test
    public void capabilities() {
        ClassMonitorAgent agent = new ClassMonitorAgent();
        Set<MonitorCapability> caps = agent.capabilities();
        assertEquals(1, caps.size());
        assertTrue(caps.contains(MonitorCapability.CLASS));
    }

    @Test
    public void summarizeClassesWithNullInstrumentation() {
        StubChannel channel = new StubChannel("class-test");
        ClassMonitorAgent agent = new ClassMonitorAgent();

        agent.attach(null, "{}", channel);
        String summary = agent.status();
        assertNotNull(summary);
    }

    @Test
    public void summarizeClassesWithFakeInstrumentation() {
        StubChannel channel = new StubChannel("class-test");
        ClassMonitorAgent agent = new ClassMonitorAgent();

        FakeInstrumentation fakeInst = new FakeInstrumentation();
        agent.attach(fakeInst, "{}", channel);

        String summary = agent.summarizeLoadedClasses(fakeInst);
        assertNotNull(summary);
        assertTrue(summary.contains("\"loaded_class_count\""));
    }

    @Test
    public void detachClearsState() {
        StubChannel channel = new StubChannel("class-test");
        ClassMonitorAgent agent = new ClassMonitorAgent();
        agent.attach(null, "{}", channel);
        agent.detach();
    }

    private static class StubChannel implements AgentDataChannel {
        private final String id;
        StubChannel(String id) { this.id = id; }
        @Override public void send(String agentId, String jsonPayload) {}
        @Override public String getAgentId() { return id; }
        @Override public Set<MonitorCapability> getCapabilities() { return EnumSet.noneOf(MonitorCapability.class); }
    }

    private static class FakeInstrumentation implements Instrumentation {
        @Override public void addTransformer(java.lang.instrument.ClassFileTransformer transformer, boolean canRetransform) {}
        @Override public void addTransformer(java.lang.instrument.ClassFileTransformer transformer) {}
        @Override public boolean removeTransformer(java.lang.instrument.ClassFileTransformer transformer) { return true; }
        @Override public boolean isRetransformClassesSupported() { return false; }
        @Override public void retransformClasses(Class<?>... classes) {}
        @Override public boolean isRedefineClassesSupported() { return false; }
        @Override public void redefineClasses(ClassDefinition... definitions) {}
        @Override public boolean isModifiableClass(Class<?> theClass) { return true; }
        @Override public Class<?>[] getAllLoadedClasses() {
            return new Class<?>[]{
                String.class, Integer.class, Object.class, System.class
            };
        }
        @Override public Class<?>[] getInitiatedClasses(ClassLoader loader) { return new Class<?>[0]; }
        @Override public long getObjectSize(Object objectToSize) { return 0; }
        @Override public void appendToBootstrapClassLoaderSearch(JarFile jarfile) {}
        @Override public void appendToSystemClassLoaderSearch(JarFile jarfile) {}
        @Override public boolean isNativeMethodPrefixSupported() { return false; }
        @Override public void setNativeMethodPrefix(java.lang.instrument.ClassFileTransformer transformer, String prefix) {}
    }
}
