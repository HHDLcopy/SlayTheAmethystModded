package io.stamethyst.agent;

import io.stamethyst.agent.connection.AgentConnectionManager;
import io.stamethyst.agent.monitors.impl.PlayMonitor;
import io.stamethyst.agent.monitors.impl.TracingMonitor;
import io.stamethyst.agent.monitors.MonitorRegistry;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Properties;

public final class AgentConnector {

    private static final int DEFAULT_PORT = 9099;

    private static MonitorRegistry registry;
    private static Instrumentation instrumentation;
    private static AgentConnectionManager connectionManager;

    /**
     * Captured from the first non-delegating ClassLoader seen during premain
     * ClassFileTransformer callbacks.  This is the MTS URLClassLoader that
     * loads game and mod classes, inaccessible to the agent's own system
     * ClassLoader.
     */
    public static volatile ClassLoader GAME_CLASSLOADER;

    private AgentConnector() {}

    public static void premain(String agentArgs, Instrumentation inst) {
        instrumentation = inst;

        Properties props = parseArgs(agentArgs);
        int port = Integer.parseInt(props.getProperty("port", String.valueOf(DEFAULT_PORT)));

        registry = new MonitorRegistry();
        registerBuiltinMonitors();

        // Capture the first non-agent ClassLoader on any class-load event.
        // The tracing transformer also does this but only for matching classes.
        // This transformer fires on every load, ensuring we capture early.
        if (inst != null) {
            inst.addTransformer(new ClassFileTransformer() {
                @Override
                public byte[] transform(ClassLoader loader, String internalName,
                                        Class<?> classBeingRedefined,
                                        ProtectionDomain protectionDomain,
                                        byte[] classfileBuffer) {
                    if (loader != null && GAME_CLASSLOADER == null
                        && !internalName.startsWith("io/stamethyst/agent/")
                        && !internalName.startsWith("java/")
                        && !internalName.startsWith("sun/")
                        && !internalName.startsWith("jdk/")) {
                        GAME_CLASSLOADER = loader;
                        System.out.println("[agent-connector] captured game ClassLoader: "
                            + loader.getClass().getName());
                    }
                    return null; // no transformation
                }
            }, false);
        }

        String premainSpec = props.getProperty("spec");
        if (premainSpec != null && !premainSpec.isEmpty()) {
            try {
                MonitorRegistry.ParsedSpec parsed = registry.parseSpec(premainSpec);
                io.stamethyst.agent.monitors.Monitor monitor = registry.create(premainSpec, inst, null);
                if (monitor != null) {
                    String agentId = parsed.prefix + "-premain";
                    io.stamethyst.agent.channel.AgentDataChannel channel =
                        new io.stamethyst.agent.channel.PremainDataChannel(agentId);
                    monitor.attach(inst, parsed.argsJson, channel);
                    System.out.println("[agent-connector] premain auto-attach: " + agentId + " spec=" + premainSpec);
                }
            } catch (Throwable e) {
                System.err.println("[agent-connector] premain attach failed: " + e.getMessage());
                e.printStackTrace(System.err);
            }
        }

        connectionManager = new AgentConnectionManager(registry, instrumentation, port);
        try {
            connectionManager.start();
        } catch (Exception e) {
            System.err.println("[agent-connector] failed to start TCP server: " + e.getMessage());
        }
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        premain(agentArgs, inst);
    }

    public static MonitorRegistry getRegistry() {
        return registry;
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }

    public static AgentConnectionManager getConnectionManager() {
        return connectionManager;
    }

    private static void registerBuiltinMonitors() {
        registry.register("tracing", new MonitorRegistry.MonitorFactory() {
            @Override
            public io.stamethyst.agent.monitors.Monitor create(Instrumentation inst, String argsJson, io.stamethyst.agent.channel.AgentDataChannel channel) {
                return new TracingMonitor();
            }
        });

        registry.register("play", new MonitorRegistry.MonitorFactory() {
            @Override
            public io.stamethyst.agent.monitors.Monitor create(Instrumentation inst, String argsJson, io.stamethyst.agent.channel.AgentDataChannel channel) {
                return new PlayMonitor();
            }
        });
    }

    private static Properties parseArgs(String agentArgs) {
        Properties props = new Properties();
        if (agentArgs == null || agentArgs.isEmpty()) {
            return props;
        }
        String[] pairs = agentArgs.split(",");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                props.setProperty(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            } else if (eq < 0 && !pair.trim().isEmpty()) {
                props.setProperty(pair.trim(), "");
            }
        }
        return props;
    }
}
