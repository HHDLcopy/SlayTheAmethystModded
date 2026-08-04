package io.stamethyst.arthas;

import com.taobao.arthas.core.shell.term.SignalHandler;
import org.junit.Test;

import java.net.ServerSocket;
import java.net.Socket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SocketTermTest {
    @Test
    public void interruptDeliversSigintToRegisteredHandler() throws Exception {
        ServerSocket server = new ServerSocket(0);
        Socket client = new Socket("127.0.0.1", server.getLocalPort());
        SocketTerm term = new SocketTerm(server.accept());
        final int[] signal = {-1};

        term.interruptHandler(new SignalHandler() {
            @Override
            public boolean deliver(int value) {
                signal[0] = value;
                return true;
            }
        });

        assertTrue(term.interrupt());
        assertEquals(3, signal[0]);

        term.close();
        client.close();
        server.close();
    }

    @Test
    public void inputArrivingBeforeReadlineIsDeliveredAfterRegistration() throws Exception {
        ServerSocket server = new ServerSocket(0);
        Socket client = new Socket("127.0.0.1", server.getLocalPort());
        SocketTerm term = new SocketTerm(server.accept());
        final String[] input = {null};

        term.feed("version");
        term.readline("prompt", new com.taobao.arthas.core.shell.handlers.Handler<String>() {
            @Override
            public void handle(String value) {
                input[0] = value;
            }
        });

        assertEquals("version", input[0]);

        term.close();
        client.close();
        server.close();
    }
}
