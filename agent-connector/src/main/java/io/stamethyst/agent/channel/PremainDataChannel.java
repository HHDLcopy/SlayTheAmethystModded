package io.stamethyst.agent.channel;

import io.stamethyst.agent.monitors.MonitorCapability;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public class PremainDataChannel implements AgentDataChannel {

    private static String resolveLogPath() {
        // Try system property first
        String prop = System.getProperty("amethyst.agent.log");
        if (prop != null && !prop.isEmpty()) return prop;
        // Fallback: write next to the agent jar in the sts directory
        String agentPath = System.getProperty("amethyst.mts.mod_file_list");
        if (agentPath != null && !agentPath.isEmpty()) {
            return new File(new File(agentPath).getParentFile(), "agent_premain.jsonl").getAbsolutePath();
        }
        return "/tmp/agent_premain.jsonl";
    }

    private static final String LOG_FILE = resolveLogPath();

    private final String agentId;
    private final CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<String>();
    private Writer fileWriter;

    public PremainDataChannel(String agentId) {
        this.agentId = agentId;
        try {
            File f = new File(LOG_FILE);
            f.getParentFile().mkdirs();
            fileWriter = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
            // write header event so we know the channel is alive
            fileWriter.write("{\"type\":\"agent_start\",\"agent_id\":\"" + agentId + "\"}\n");
            fileWriter.flush();
        } catch (Exception e) {
            fileWriter = null;
        }
    }

    @Override
    public void send(String agentId, String jsonPayload) {
        events.add(jsonPayload);
        if (fileWriter != null) {
            try {
                fileWriter.write(jsonPayload + "\n");
                fileWriter.flush();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public String getAgentId() {
        return agentId;
    }

    @Override
    public Set<MonitorCapability> getCapabilities() {
        return Collections.emptySet();
    }

    public CopyOnWriteArrayList<String> getEvents() {
        return events;
    }
}
