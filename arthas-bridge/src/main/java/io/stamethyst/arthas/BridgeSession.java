package io.stamethyst.arthas;

import com.taobao.arthas.core.shell.Shell;
import com.taobao.arthas.core.shell.ShellServer;

import java.io.FileWriter;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.Socket;

public class BridgeSession implements Runnable {
    private final Socket socket;
    private final ShellServer shellServer;

    public BridgeSession(Socket socket, ShellServer shellServer) {
        this.socket = socket;
        this.shellServer = shellServer;
    }

    private static void log(String msg) {
        try {
            PrintWriter w = new PrintWriter(new FileWriter(
                "/data/data/io.stamethyst/files/arthas-bridge.log", true));
            w.println("[BridgeSession] " + msg);
            w.flush(); w.close();
        } catch (Exception ignored) {}
    }

    @Override
    public void run() {
        try {
            log("creating SocketTerm");
            SocketTerm term = new SocketTerm(socket);
            log("SocketTerm created, calling shellServer.createShell");
            Shell shell = shellServer.createShell(term);
            log("shellServer.createShell returned: " + shell);
            try {
                java.lang.reflect.Method init = shell.getClass()
                    .getDeclaredMethod("init");
                init.setAccessible(true);
                init.invoke(shell);
                java.lang.reflect.Method rl = shell.getClass()
                    .getDeclaredMethod("readline");
                rl.setAccessible(true);
                rl.invoke(shell);
                log("shell.init()+readline() done");
            } catch (Exception e) {
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                log("init/readline FAILED: " + sw);
            }
            log("entering read loop");

            socket.getOutputStream().write("arthas-bridge ready\n".getBytes("UTF-8"));
            socket.getOutputStream().flush();

            InputStream in = socket.getInputStream();
            byte[] buf = new byte[8192];
            StringBuilder lineBuf = new StringBuilder();
            log("entering read loop");

            while (true) {
                int n = in.read(buf);
                if (n < 0) break;
                for (int i = 0; i < n; i++) {
                    char c = (char) (buf[i] & 0xFF);
                    if (c == '\n') {
                        term.feed(lineBuf.toString());
                        lineBuf.setLength(0);
                    } else if (c != '\r') {
                        lineBuf.append(c);
                    }
                }
            }
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            log("EXCEPTION: " + sw.toString());
        } catch (Throwable t) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            log("THROWABLE: " + sw.toString());
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }
}
