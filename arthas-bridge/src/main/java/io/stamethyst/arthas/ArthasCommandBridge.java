package io.stamethyst.arthas;

import com.taobao.arthas.core.command.BuiltinCommandPack;
import com.taobao.arthas.core.shell.ShellServer;
import com.taobao.arthas.core.shell.command.Command;
import com.taobao.arthas.core.shell.command.CommandResolver;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Properties;

public class ArthasCommandBridge {
    static PrintWriter logger;

    /**
     * Bridge listener state.  Static because {@code agentmain} may be invoked
     * repeatedly on the same JVM (re-attach); the listener must survive an
     * Arthas {@code stop} which destroys the bootstrap but cannot close this
     * socket.
     */
    private static final Object LOCK = new Object();
    private static java.net.ServerSocket serverSocket;
    private static int listeningPort = -1;

    public static void agentmain(String args, final Instrumentation inst) {
        new Thread(new Runnable() {
            public void run() { new ArthasCommandBridge().start(args, inst); }
        }, "arthas-bridge").start();
    }

    static void log(String msg) {
        if (logger != null) { logger.println("[arthas-bridge] " + msg); logger.flush(); }
    }

    public static void runOptionalDiagnostics(Instrumentation inst) {
        setupProcFSFallback();
        setupAsyncProfilerFlat();
    }

    /** True when a usable listener for {@code port} already exists. */
    private static boolean hasLiveListener(int port) {
        synchronized (LOCK) {
            return serverSocket != null
                && !serverSocket.isClosed()
                && listeningPort == port;
        }
    }

    /** Outcome of {@link #bindOrReuse(int)}. */
    public static final class Listener {
        public final java.net.ServerSocket socket;
        /** True when an earlier attach already owned this listener. */
        public final boolean reused;

        public Listener(java.net.ServerSocket socket, boolean reused) {
            this.socket = socket;
            this.reused = reused;
        }
    }

    /**
     * Bind the bridge listener, or hand back the one a previous attach left
     * running.  Returns {@code null} only when the port is held by something
     * outside this bridge, which is the sole genuine failure.
     */
    static Listener bindOrReuse(int port) {
        synchronized (LOCK) {
            if (hasLiveListener(port)) {
                return new Listener(serverSocket, true);
            }
            try {
                java.net.ServerSocket server = new java.net.ServerSocket(port);
                serverSocket = server;
                listeningPort = port;
                return new Listener(server, false);
            } catch (java.net.BindException be) {
                log("port " + port + " held by another process: " + be);
                return null;
            } catch (java.io.IOException e) {
                log("failed to bind " + port + ": " + e);
                return null;
            }
        }
    }

    /**
     * Close the bridge listener and drop static references.  Wakes a blocked
     * {@code accept()} by closing the socket underneath it.  Called from the
     * explicit shutdown path; Arthas {@code stop} alone does not reach here.
     */
    public static void shutdownBridge() {
        java.net.ServerSocket toClose;
        synchronized (LOCK) {
            toClose = serverSocket;
            serverSocket = null;
            listeningPort = -1;
        }
        if (toClose != null) {
            try {
                toClose.close();
                log("bridge listener closed");
            } catch (Throwable e) {
                log("shutdownBridge close failed: " + e);
            }
        }
    }

    void start(String args, Instrumentation inst) {
        try {
            logger = new PrintWriter(new FileWriter(
                "/data/data/io.stamethyst/files/arthas-bridge.log", true));
        } catch (Exception ignored) {}

        Properties props = parseArgs(args);
        int port = Integer.parseInt(props.getProperty("port", "8099"));

        try {
            log("starting on port " + port);

            CommonSuperBridge.setInstrumentation(inst);
            inst.addTransformer(new ClassMetaClassWriterTransformer(), true);

            CommandResolver resolver = new BuiltinCommandPack(Collections.<String>emptyList());
            resolver.commands().add(Command.create(MetaspaceCommand.class));

            com.taobao.arthas.core.server.ArthasBootstrap bootstrap =
                com.taobao.arthas.core.server.ArthasBootstrapCompat
                    .createWithoutNetty(inst,
                        new java.util.HashMap<String, String>());

            com.taobao.arthas.core.shell.ShellServer shellServer =
                bootstrap.getShellServer();
            shellServer.registerCommandResolver(resolver);
            log("bootstrap ready, shellServer=" + shellServer);

            Listener listener = bindOrReuse(port);
            if (listener == null) {
                log("START FAILED: cannot listen on " + port);
                return;
            }

            // Idempotent attach: the bootstrap and resolver above were already
            // refreshed, and the running accept loop resolves the shell server
            // per connection, so it picks up the new bootstrap on its own.
            if (listener.reused) {
                log("listener already active on " + port + ", reusing (idempotent attach)");
                return;
            }

            log("listening on " + port);
            Thread diagnostics = new Thread(
                new OptionalDiagnostics(inst),
                "arthas-optional-diagnostics");
            diagnostics.setDaemon(true);
            diagnostics.start();
            acceptLoop(listener.socket, inst);
        } catch (Throwable e) {
            log("START FAILED: " + e);
            e.printStackTrace(logger);
        }
    }

    /**
     * Optional native/profiler setup must not delay the shell handshake.
     * Some Android runtimes block while loading procfs helpers, and
     * async-profiler can be rejected by device perf policy.  The bridge is
     * useful without either enhancement, so initialize them after the socket
     * is accepting connections.
     */
    /**
     * Accept connections until the listener is closed.  The shell server is
     * resolved per connection instead of being captured, so a bootstrap
     * replaced by a later attach is picked up without restarting the loop.
     */
    private static void acceptLoop(java.net.ServerSocket server, Instrumentation inst) {
        while (!server.isClosed()) {
            java.net.Socket client;
            try {
                client = server.accept();
            } catch (java.io.IOException e) {
                if (server.isClosed()) {
                    log("accept loop exiting: listener closed");
                } else {
                    log("accept failed: " + e);
                }
                return;
            }
            log("client connected");
            retransformClassMetaClassWriter(inst);

            com.taobao.arthas.core.server.ArthasBootstrap current;
            try {
                current = com.taobao.arthas.core.server.ArthasBootstrap.getInstance();
            } catch (IllegalStateException ise) {
                // Bootstrap was destroyed by an Arthas 'stop' command.
                // Close this connection and shut down the listener so the port
                // is released and the Python shutdown() polling loop can observe it.
                log("bootstrap destroyed, shutting down bridge listener");
                try { client.close(); } catch (Exception ignored) {}
                shutdownBridge();
                return;
            }
            ShellServer currentShellServer =
                current == null ? null : current.getShellServer();
            if (currentShellServer == null) {
                log("no live shellServer; closing connection");
                try { client.close(); } catch (Exception ignored) {}
                continue;
            }
            new Thread(new BridgeSession(client, currentShellServer)).start();
        }
    }

    private static void setupProcFSFallback() {
        ProcFSBridge.ensureLoaded();
        if (ProcFSBridge.isLoaded()) {
            ProcFSThreadCpuPatch.install();
        }
    }

    private static void setupAsyncProfilerFlat() {
        try {
            java.net.URL codeSource = com.taobao.arthas.core.command.monitor200.ProfilerCommand.class
                .getProtectionDomain().getCodeSource().getLocation();
            File jarFile = new File(codeSource.toURI().getSchemeSpecificPart());
            File arthasHome = jarFile.getParentFile();

            File flatSo = new File(arthasHome, "libasyncProfiler-linux-arm64.so");
            if (!flatSo.isFile()) {
                log("async-profiler .so not found at " + flatSo + ", skipping");
                return;
            }

            String flatPath = flatSo.getAbsolutePath();
            one.profiler.AsyncProfiler ap = one.profiler.AsyncProfiler.getInstance(flatPath);
            log("async-profiler loaded, version=" + ap.getVersion());

            Field pcField = com.taobao.arthas.core.command.monitor200.ProfilerCommand.class
                .getDeclaredField("profiler");
            pcField.setAccessible(true);
            pcField.set(null, ap);

            Field apInst = one.profiler.AsyncProfiler.class.getDeclaredField("instance");
            apInst.setAccessible(true);
            apInst.set(null, ap);

            log("async-profiler injected into ProfilerCommand.profiler and AsyncProfiler.instance");
        } catch (Throwable e) {
            log("setupAsyncProfilerFlat failed: " + e);
        }
    }

    private static void retransformClassMetaClassWriter(Instrumentation inst) {
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if ("com.alibaba.bytekit.asm.ClassMetaClassWriter".equals(c.getName())) {
                try {
                    inst.retransformClasses(c);
                } catch (Throwable ignored) {}
                return;
            }
        }
    }

    private static Properties parseArgs(String args) {
        Properties props = new Properties();
        if (args == null || args.isEmpty()) return props;
        for (String part : args.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0)
                props.setProperty(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        return props;
    }
}
