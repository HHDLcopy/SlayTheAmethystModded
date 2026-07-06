package io.stamethyst.agent.connection;

import io.stamethyst.agent.monitors.MonitorRegistry;

import java.io.Closeable;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class AgentConnectionManager implements Closeable {

    private static final int DEFAULT_PORT = 9099;

    private final MonitorRegistry registry;
    private final Instrumentation instrumentation;
    private final int configuredPort;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private final List<AgentSession> sessions = new CopyOnWriteArrayList<AgentSession>();

    public AgentConnectionManager(MonitorRegistry registry, Instrumentation instrumentation) {
        this(registry, instrumentation, DEFAULT_PORT);
    }

    public AgentConnectionManager(MonitorRegistry registry, Instrumentation instrumentation, int port) {
        this.registry = registry;
        this.instrumentation = instrumentation;
        this.configuredPort = port;
    }

    public void start() throws IOException {
        if (running.get()) return;
        serverSocket = new ServerSocket(configuredPort, 50, java.net.InetAddress.getByName("127.0.0.1"));
        running.set(true);

        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running.get()) {
                    try {
                        Socket client = serverSocket.accept();
                        AgentSession session = new AgentSession(client, registry, instrumentation);
                        sessions.add(session);
                        Thread sessionThread = new Thread(session, "agent-session");
                        sessionThread.setDaemon(true);
                        sessionThread.start();
                    } catch (IOException e) {
                        if (running.get()) {
                            // unexpected error during accept
                        }
                    }
                }
            }
        }, "agent-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getPort() {
        if (serverSocket != null && serverSocket.isBound()) {
            return serverSocket.getLocalPort();
        }
        return configuredPort;
    }

    @Override
    public void close() {
        running.set(false);
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}
        if (acceptThread != null) {
            try {
                acceptThread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
