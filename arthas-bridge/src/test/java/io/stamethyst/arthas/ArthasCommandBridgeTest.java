package io.stamethyst.arthas;

import org.junit.After;
import org.junit.Test;

import java.net.ServerSocket;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Listener lifecycle for the bridge.  These cover the half-dead JVM state
 * where an Arthas 'stop' destroyed the bootstrap but left the ServerSocket
 * bound, which used to make every later attach fail with BindException.
 */
public class ArthasCommandBridgeTest {

    @After
    public void tearDown() {
        ArthasCommandBridge.shutdownBridge();
    }

    private static int freePort() throws Exception {
        ServerSocket probe = new ServerSocket(0);
        int port = probe.getLocalPort();
        probe.close();
        return port;
    }

    @Test
    public void repeatedAttachReusesTheSameListener() throws Exception {
        int port = freePort();

        ArthasCommandBridge.Listener first = ArthasCommandBridge.bindOrReuse(port);
        assertNotNull("first attach must bind", first);
        assertFalse("first attach is a fresh bind", first.reused);

        ArthasCommandBridge.Listener second = ArthasCommandBridge.bindOrReuse(port);
        assertNotNull("second attach must not fail with BindException", second);
        assertTrue("second attach reuses the live listener", second.reused);
        assertSame("same underlying ServerSocket", first.socket, second.socket);
        assertFalse(second.socket.isClosed());
    }

    @Test
    public void portIsBindableAgainAfterShutdownBridge() throws Exception {
        int port = freePort();

        ArthasCommandBridge.Listener listener = ArthasCommandBridge.bindOrReuse(port);
        assertNotNull(listener);
        assertFalse(listener.socket.isClosed());

        ArthasCommandBridge.shutdownBridge();
        assertTrue("shutdownBridge closes the socket", listener.socket.isClosed());

        // The port must be free for an independent binder now.
        ServerSocket rebound = new ServerSocket(port);
        assertFalse(rebound.isClosed());
        rebound.close();
    }

    @Test
    public void attachAfterShutdownBindsAFreshListener() throws Exception {
        int port = freePort();

        ArthasCommandBridge.Listener first = ArthasCommandBridge.bindOrReuse(port);
        assertNotNull(first);
        ArthasCommandBridge.shutdownBridge();

        ArthasCommandBridge.Listener second = ArthasCommandBridge.bindOrReuse(port);
        assertNotNull("re-attach after shutdown must bind again", second);
        assertFalse("it is a fresh bind, not a reuse", second.reused);
        assertFalse(second.socket.isClosed());
    }

    @Test
    public void bindFailsWhenPortHeldByAnotherProcess() throws Exception {
        ServerSocket foreign = new ServerSocket(0);
        try {
            // No bridge listener exists for this port, so this is a genuine
            // external conflict rather than our own stale socket.
            assertNull(ArthasCommandBridge.bindOrReuse(foreign.getLocalPort()));
        } finally {
            foreign.close();
        }
    }

    @Test
    public void shutdownBridgeIsSafeWhenNoListenerExists() {
        ArthasCommandBridge.shutdownBridge();
        ArthasCommandBridge.shutdownBridge();
    }
}
