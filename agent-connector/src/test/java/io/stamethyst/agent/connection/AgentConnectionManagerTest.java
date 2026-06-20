package io.stamethyst.agent.connection;

import io.stamethyst.agent.monitors.SpecMonitorRegistry;
import org.junit.After;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

import static org.junit.Assert.*;

public class AgentConnectionManagerTest {

    private AgentConnectionManager manager;

    @After
    public void tearDown() throws Exception {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    public void startAndStop() throws Exception {
        SpecMonitorRegistry registry = new SpecMonitorRegistry();
        manager = new AgentConnectionManager(registry, null, 0);
        manager.start();
        assertTrue(manager.isRunning());
        manager.close();
        assertFalse(manager.isRunning());
    }

    @Test
    public void bindsPortAndAcceptsConnections() throws Exception {
        SpecMonitorRegistry registry = new SpecMonitorRegistry();
        manager = new AgentConnectionManager(registry, null, 0);
        manager.start();
        assertTrue(manager.isRunning());

        int port = manager.getPort();
        assertTrue(port > 0);

        Socket client = new Socket("127.0.0.1", port);
        PrintWriter writer = new PrintWriter(client.getOutputStream(), true);
        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));

        writer.println("QUIT");
        String response = reader.readLine();
        assertEquals("BYE", response);

        client.close();
        manager.close();
        assertFalse(manager.isRunning());
    }

    @Test
    public void multipleConnections() throws Exception {
        SpecMonitorRegistry registry = new SpecMonitorRegistry();
        manager = new AgentConnectionManager(registry, null, 0);
        manager.start();
        int port = manager.getPort();

        for (int i = 0; i < 3; i++) {
            Socket client = new Socket("127.0.0.1", port);
            PrintWriter writer = new PrintWriter(client.getOutputStream(), true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));

            writer.println("LIST");
            String response = reader.readLine();
            assertEquals("AGENTS", response.trim());

            writer.println("QUIT");
            assertEquals("BYE", reader.readLine());
            client.close();
        }

        manager.close();
    }

    @Test
    public void closeTwiceDoesNotThrow() throws Exception {
        SpecMonitorRegistry registry = new SpecMonitorRegistry();
        manager = new AgentConnectionManager(registry, null, 0);
        manager.start();
        manager.close();
        manager.close();
    }

    @Test
    public void notRunningBeforeStart() {
        SpecMonitorRegistry registry = new SpecMonitorRegistry();
        manager = new AgentConnectionManager(registry, null);
        assertFalse(manager.isRunning());
    }

    @Test
    public void defaultPort() {
        SpecMonitorRegistry registry = new SpecMonitorRegistry();
        manager = new AgentConnectionManager(registry, null);
        assertEquals(9099, manager.getPort());
    }

    @Test
    public void customPort() {
        SpecMonitorRegistry registry = new SpecMonitorRegistry();
        manager = new AgentConnectionManager(registry, null, 12345);
        assertEquals(12345, manager.getPort());
    }

    @Test
    public void portResolvedAfterStart() throws Exception {
        SpecMonitorRegistry registry = new SpecMonitorRegistry();
        manager = new AgentConnectionManager(registry, null, 0);
        assertEquals(0, manager.getPort());
        manager.start();
        assertTrue(manager.getPort() > 0);
        manager.close();
    }
}
