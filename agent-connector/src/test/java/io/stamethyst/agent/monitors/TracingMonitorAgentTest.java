package io.stamethyst.agent.monitors;

import io.stamethyst.agent.channel.AgentDataChannel;
import io.stamethyst.agent.monitors.impl.TracingMonitorAgent;
import org.junit.Test;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class TracingMonitorAgentTest {

    @Test
    public void classPatternMatches() {
        assertTrue(TracingMonitorAgent.matchesPattern("com.megacrit.cardcrawl.cards.DamageCard", "com.megacrit.cardcrawl.cards.*"));
        assertTrue(TracingMonitorAgent.matchesPattern("com.megacrit.cardcrawl.characters.Ironclad", "com.megacrit.cardcrawl.*"));
        assertTrue(TracingMonitorAgent.matchesPattern("com.badlogic.gdx.Gdx", "com.badlogic.*"));
    }

    @Test
    public void classPatternNoMatch() {
        assertFalse(TracingMonitorAgent.matchesPattern("com.megacrit.cardcrawl.cards.DamageCard", "com.badlogic.*"));
        assertFalse(TracingMonitorAgent.matchesPattern("java.lang.String", "com.megacrit.*"));
    }

    @Test
    public void classPatternExactMatch() {
        assertTrue(TracingMonitorAgent.matchesPattern("com.megacrit.cardcrawl.cards.DamageCard",
            "com.megacrit.cardcrawl.cards.DamageCard"));
        assertFalse(TracingMonitorAgent.matchesPattern("com.megacrit.cardcrawl.cards.OtherCard",
            "com.megacrit.cardcrawl.cards.DamageCard"));
    }

    @Test
    public void methodPatternMatches() {
        assertTrue(TracingMonitorAgent.matchesMethod("render", new String[]{"render", "update"}));
        assertTrue(TracingMonitorAgent.matchesMethod("update", new String[]{"render", "update"}));
        assertFalse(TracingMonitorAgent.matchesMethod("dispose", new String[]{"render", "update"}));
    }

    @Test
    public void nullMethodFilterMeansAllMethods() {
        assertTrue(TracingMonitorAgent.matchesMethod("anything", null));
        assertTrue(TracingMonitorAgent.matchesMethod("foo", null));
    }

    @Test
    public void capabilities() {
        StubChannel channel = new StubChannel("test");
        TracingMonitorAgent agent = new TracingMonitorAgent();
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
