package io.stamethyst.arthas;

import com.taobao.arthas.core.shell.cli.Completion;
import com.taobao.arthas.core.shell.handlers.Handler;
import com.taobao.arthas.core.shell.term.Term;
import io.termd.core.function.Function;

import java.io.FileWriter;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;

public class SocketTerm implements Term {
    private static PrintWriter _log;
    static {
        try { _log = new PrintWriter(new FileWriter(
            "/data/data/io.stamethyst/files/arthas-term.log", true)); } catch (Exception ignored) {}
    }
    private static void log(String s) {
        if (_log != null) { _log.println(s); _log.flush(); }
    }

    private final Socket socket;
    private OutputStream out;
    private Handler<String> pendingReadline;
    private Handler<String> stdinHandler;
    private boolean closed;

    public SocketTerm(Socket socket) throws Exception {
        this.socket = socket;
        this.out = socket.getOutputStream();
    }

    void feed(String line) {
        log("feed: " + line);
        if (pendingReadline != null) {
            Handler<String> h = pendingReadline;
            pendingReadline = null;
            h.handle(line);
        } else {
            log("feed DROPPED (no pendingReadline)");
        }
    }

    @Override public String type() { return "dumb"; }
    @Override public int width() { return 120; }
    @Override public int height() { return 40; }

    @Override public Term write(String text) {
        int n = text.length();
        log(String.format("write(%d): %s", n, text.replace("\n","\\n").replace("\r","\\r")));
        try { 
            out.write(text.getBytes("UTF-8")); 
            out.flush();
            log("  flushed " + n + " bytes OK");
        } catch (Exception e) { 
            log("write ERR: " + e);
        }
        return this;
    }
    @Override public Term resizehandler(Handler<Void> h) { return this; }
    @Override public Term stdinHandler(Handler<String> h) { this.stdinHandler = h; log("stdinHandler set"); return this; }
    @Override public Term stdoutHandler(Function<String, String> fn) { return this; }
    @Override public long lastAccessedTime() { return System.currentTimeMillis(); }
    @Override public Term echo(String text) { return write(text); }
    @Override public Term setSession(com.taobao.arthas.core.shell.session.Session s) { return this; }
    @Override public Term interruptHandler(com.taobao.arthas.core.shell.term.SignalHandler h) { return this; }
    @Override public Term suspendHandler(com.taobao.arthas.core.shell.term.SignalHandler h) { return this; }
    @Override public void readline(String prompt, Handler<String> handler) {
        log("readline: " + prompt.trim());
        this.pendingReadline = handler;
        write(prompt);
    }
    @Override public void readline(String prompt, Handler<String> handler, Handler<Completion> ch) { readline(prompt, handler); }
    @Override public Term closeHandler(Handler<Void> h) { return this; }
    @Override public void close() { closed = true; try { socket.close(); } catch (Exception ignored) {} }
}
