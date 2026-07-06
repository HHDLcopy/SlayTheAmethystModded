package io.stamethyst.agent.monitors;

import io.stamethyst.agent.channel.AgentDataChannel;
import io.stamethyst.agent.monitors.impl.TracingMonitor;
import org.junit.Test;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class TracingMonitorTest {

    @Test
    public void classPatternMatches() {
        assertTrue(TracingMonitor.matchesPattern("com.megacrit.cardcrawl.cards.DamageCard", "com.megacrit.cardcrawl.cards.*"));
        assertTrue(TracingMonitor.matchesPattern("com.megacrit.cardcrawl.characters.Ironclad", "com.megacrit.cardcrawl.*"));
        assertTrue(TracingMonitor.matchesPattern("com.badlogic.gdx.Gdx", "com.badlogic.*"));
    }

    @Test
    public void classPatternNoMatch() {
        assertFalse(TracingMonitor.matchesPattern("com.megacrit.cardcrawl.cards.DamageCard", "com.badlogic.*"));
        assertFalse(TracingMonitor.matchesPattern("java.lang.String", "com.megacrit.*"));
    }

    @Test
    public void classPatternExactMatch() {
        assertTrue(TracingMonitor.matchesPattern("com.megacrit.cardcrawl.cards.DamageCard",
            "com.megacrit.cardcrawl.cards.DamageCard"));
        assertFalse(TracingMonitor.matchesPattern("com.megacrit.cardcrawl.cards.OtherCard",
            "com.megacrit.cardcrawl.cards.DamageCard"));
    }

    @Test
    public void methodPatternMatches() {
        assertTrue(TracingMonitor.matchesMethod("render", new String[]{"render", "update"}));
        assertTrue(TracingMonitor.matchesMethod("update", new String[]{"render", "update"}));
        assertFalse(TracingMonitor.matchesMethod("dispose", new String[]{"render", "update"}));
    }

    @Test
    public void nullMethodFilterMeansAllMethods() {
        assertTrue(TracingMonitor.matchesMethod("anything", null));
        assertTrue(TracingMonitor.matchesMethod("foo", null));
    }

    @Test
    public void capabilities() {
        StubChannel channel = new StubChannel("test");
        TracingMonitor agent = new TracingMonitor();
        Set<MonitorCapability> caps = agent.capabilities();
        assertEquals(1, caps.size());
        assertTrue(caps.contains(MonitorCapability.TRACING));
    }

    private static class StubChannel implements AgentDataChannel {
        private final String id;
        private final List<String> events = new ArrayList<String>();
        StubChannel(String id) { this.id = id; }
        @Override public void send(String agentId, String jsonPayload) { events.add(jsonPayload); }
        @Override public String getAgentId() { return id; }
        @Override public Set<MonitorCapability> getCapabilities() { return EnumSet.noneOf(MonitorCapability.class); }
    }
}
