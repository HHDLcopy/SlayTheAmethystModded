package io.stamethyst.arthas;

import com.taobao.arthas.core.command.BuiltinCommandPack;
import com.taobao.arthas.core.shell.ShellServer;
import com.taobao.arthas.core.shell.command.CommandResolver;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.Properties;

public class ArthasCommandBridge {
    static PrintWriter logger;

    public static void agentmain(String args, final Instrumentation inst) {
        new Thread(new Runnable() {
            public void run() { new ArthasCommandBridge().start(args, inst); }
        }, "arthas-bridge").start();
    }

    static void log(String msg) {
        if (logger != null) { logger.println("[arthas-bridge] " + msg); logger.flush(); }
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

            com.taobao.arthas.core.server.ArthasBootstrap bootstrap =
                com.taobao.arthas.core.server.ArthasBootstrapCompat
                    .createWithoutNetty(inst,
                        new java.util.HashMap<String, String>());

            com.taobao.arthas.core.shell.ShellServer shellServer =
                bootstrap.getShellServer();
            shellServer.registerCommandResolver(resolver);
            log("bootstrap ready, shellServer=" + shellServer);

            java.net.ServerSocket server = new java.net.ServerSocket(port);
            log("listening on " + port);

            while (true) {
                java.net.Socket client = server.accept();
                log("client connected");
                retransformClassMetaClassWriter(inst);
                new Thread(new BridgeSession(client, shellServer)).start();
            }
        } catch (Throwable e) {
            log("START FAILED: " + e);
            e.printStackTrace(logger);
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
