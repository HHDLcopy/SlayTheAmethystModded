package io.stamethyst.agent.channel;

import io.stamethyst.agent.monitors.MonitorCapability;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class TcpDataChannelTest {

    @Test
    public void sendDataOverSocket() throws Exception {
        ServerSocket server = new ServerSocket(0);
        int port = server.getLocalPort();
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<String> receivedLine = new AtomicReference<String>();

        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket client = server.accept();
                    BufferedReader br = new BufferedReader(new InputStreamReader(client.getInputStream()));
                    String line = br.readLine();
                    receivedLine.set(line);
                    br.close();
                    client.close();
                } catch (Exception ignored) {
                }
            }
        });
        reader.start();

        Socket socket = new Socket("127.0.0.1", port);
        PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);

        Set<MonitorCapability> caps = EnumSet.of(MonitorCapability.TRACING);
        TcpDataChannel channel = new TcpDataChannel(writer, "test-1", caps);
        channel.setSubscribed(true);

        channel.send("test-1", "{\"event\":\"test\"}");
        writer.flush();

        reader.join(2000);
        server.close();
        socket.close();

        assertNotNull(receivedLine.get());
        assertEquals("DATA test-1 {\"event\":\"test\"}", receivedLine.get());
    }

    @Test
    public void getAgentId() {
        Set<MonitorCapability> caps = EnumSet.of(MonitorCapability.TRACING);
        TcpDataChannel channel = new TcpDataChannel(null, "my-agent-3", caps);
        assertEquals("my-agent-3", channel.getAgentId());
    }

    @Test
    public void getCapabilities() {
        Set<MonitorCapability> caps = EnumSet.of(MonitorCapability.TRACING);
        TcpDataChannel channel = new TcpDataChannel(null, "gc-agent", caps);
        assertEquals(caps, channel.getCapabilities());
        assertTrue(channel.getCapabilities().contains(MonitorCapability.TRACING));
    }

    @Test
    public void sendDoesNotThrowOnClosedSocket() {
        Set<MonitorCapability> caps = EnumSet.of(MonitorCapability.TRACING);
        TcpDataChannel channel = new TcpDataChannel(null, "test-2", caps);
        channel.send("test-2", "{}");
    }
}
