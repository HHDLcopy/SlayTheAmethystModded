package io.stamethyst.arthas;

import com.taobao.arthas.core.shell.Shell;
import com.taobao.arthas.core.shell.ShellServer;
import com.taobao.arthas.core.shell.command.CommandResolver;
import com.taobao.arthas.core.shell.handlers.Handler;
import com.taobao.arthas.core.shell.session.Session;
import com.taobao.arthas.core.shell.system.Job;
import com.taobao.arthas.core.shell.system.JobController;
import com.taobao.arthas.core.shell.system.impl.InternalCommandManager;
import com.taobao.arthas.core.shell.system.impl.JobControllerImpl;
import com.taobao.arthas.core.shell.term.Term;
import com.taobao.arthas.core.shell.term.TermServer;
import org.junit.Test;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Session teardown.  SocketTerm.lastAccessedTime() always reports "now", so
 * ShellServerImpl's reaper never evicts a bridge session; the session must
 * close its own shell or every query leaks a ShellImpl.
 */
public class BridgeSessionTest {

    /**
     * Shell stub recording close().  Declares init()/readline() because
     * BridgeSession drives the shell lifecycle reflectively.
     */
    static class RecordingShell implements Shell {
        volatile String closeReason;
        final CountDownLatch closeLatch = new CountDownLatch(1);
        final CountDownLatch readlineLatch = new CountDownLatch(1);

        public void init() {}

        public void readline() { readlineLatch.countDown(); }

        @Override public Job createJob(List tokens) { return null; }
        @Override public Job createJob(String line) { return null; }
        @Override public JobController jobController() { return null; }
        @Override public Session session() { return null; }

        @Override
        public void close(String reason) {
            closeReason = reason;
            closeLatch.countDown();
        }
    }

    /** ShellServer stub delegating createShell to a supplied behaviour. */
    static abstract class StubShellServer extends ShellServer {
        @Override public ShellServer registerCommandResolver(CommandResolver r) { return this; }
        @Override public ShellServer registerTermServer(TermServer t) { return this; }
        @Override public Shell createShell() { return null; }
        @Override public ShellServer listen(Handler h) { return this; }
        @Override public void close(Handler h) {}
        @Override public JobControllerImpl getJobController() { return null; }
        @Override public InternalCommandManager getCommandManager() { return null; }
    }

    @Test
    public void shellIsClosedWhenReadLoopEnds() throws Exception {
        ServerSocket server = new ServerSocket(0);
        Socket client = new Socket("127.0.0.1", server.getLocalPort());
        Socket serverSide = server.accept();

        final RecordingShell shell = new RecordingShell();
        ShellServer shellServer = new StubShellServer() {
            @Override public Shell createShell(Term term) { return shell; }
        };

        Thread t = new Thread(new BridgeSession(serverSide, shellServer));
        t.start();

        // Shell lifecycle must have started before we tear the connection down.
        assertTrue("readline() should be invoked during session startup",
            shell.readlineLatch.await(5, TimeUnit.SECONDS));

        // EOF on the client side ends the read loop.
        client.close();

        assertTrue("shell.close() must run after the read loop ends",
            shell.closeLatch.await(5, TimeUnit.SECONDS));
        assertEquals("session closed", shell.closeReason);

        t.join(5000);
        assertTrue(serverSide.isClosed());
        server.close();
    }

    @Test
    public void closedShellServerDropsConnectionInsteadOfLeaking() throws Exception {
        ServerSocket server = new ServerSocket(0);
        Socket client = new Socket("127.0.0.1", server.getLocalPort());
        Socket serverSide = server.accept();

        ShellServer closedServer = new StubShellServer() {
            @Override public Shell createShell(Term term) {
                throw new IllegalStateException("Closed");
            }
        };

        Thread t = new Thread(new BridgeSession(serverSide, closedServer));
        t.start();
        t.join(5000);

        assertTrue("session must close the socket when the shell server is closed",
            serverSide.isClosed());

        client.close();
        server.close();
    }
}
