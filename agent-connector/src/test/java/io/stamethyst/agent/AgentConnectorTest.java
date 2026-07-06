package io.stamethyst.agent;

import io.stamethyst.agent.monitors.MonitorRegistry;
import org.junit.Test;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.jar.JarFile;

import static org.junit.Assert.*;

public class AgentConnectorTest {

    @Test
    public void premainRegistersBuiltinMonitors() {
        AgentConnector.premain("port=0", null);
        MonitorRegistry registry = AgentConnector.getRegistry();
        assertNotNull(registry);

        assertTrue(registry.registeredTypes().contains("tracing"));
        assertTrue(registry.registeredTypes().contains("state"));
        assertTrue(registry.registeredTypes().contains("thread"));
        assertTrue(registry.registeredTypes().contains("gc"));
        assertTrue(registry.registeredTypes().contains("class"));
        assertTrue(registry.registeredTypes().contains("dumpbytecode"));
    }

    @Test
    public void premainWithActualInstrumentation() {
        FakeInstrumentation inst = new FakeInstrumentation();
        AgentConnector.premain("port=0", inst);

        assertNotNull(AgentConnector.getInstrumentation());
        assertSame(inst, AgentConnector.getInstrumentation());
    }

    @Test
    public void premainParsesPortFromArgs() {
        FakeInstrumentation inst = new FakeInstrumentation();
        AgentConnector.premain("port=9999", inst);

        assertNotNull(AgentConnector.getConnectionManager());
    }

    @Test
    public void premainDefaultPortWhenNotSpecified() {
        FakeInstrumentation inst = new FakeInstrumentation();
        AgentConnector.premain("", inst);

        assertNotNull(AgentConnector.getConnectionManager());
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
        @Override public Class<?>[] getAllLoadedClasses() { return new Class<?>[0]; }
        @Override public Class<?>[] getInitiatedClasses(ClassLoader loader) { return new Class<?>[0]; }
        @Override public long getObjectSize(Object objectToSize) { return 0; }
        @Override public void appendToBootstrapClassLoaderSearch(JarFile jarfile) {}
        @Override public void appendToSystemClassLoaderSearch(JarFile jarfile) {}
        @Override public boolean isNativeMethodPrefixSupported() { return false; }
        @Override public void setNativeMethodPrefix(java.lang.instrument.ClassFileTransformer transformer, String prefix) {}
    }
}
