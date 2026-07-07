package io.stamethyst.arthas;

import com.alibaba.arthas.deps.org.slf4j.Logger;
import com.alibaba.arthas.deps.org.slf4j.LoggerFactory;
import com.taobao.arthas.core.shell.command.AnnotatedCommand;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.middleware.cli.annotations.Name;
import com.taobao.middleware.cli.annotations.Summary;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;

@Name("classloader-metaspace")
@Summary("Display metaspace/CompressedClassSpace memory usage via JMX")
public class MetaspaceCommand extends AnnotatedCommand {

    private static final Logger logger = LoggerFactory.getLogger(MetaspaceCommand.class);

    @Override
    public void process(CommandProcess process) {
        try {
            StringBuilder sb = new StringBuilder(512);
            long totalUsed = 0;
            long totalCommitted = 0;
            long totalMax = 0;

            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                String name = pool.getName();
                if (name.contains("Metaspace") || name.contains("Compressed Class Space")) {
                    MemoryUsage usage = pool.getUsage();
                    sb.append(name).append(":\n");
                    sb.append("  used     : ").append(formatBytes(usage.getUsed())).append('\n');
                    sb.append("  committed: ").append(formatBytes(usage.getCommitted())).append('\n');
                    sb.append("  max      : ").append(formatBytes(usage.getMax())).append('\n');
                    sb.append("  usage    : ").append(formatPercent(usage)).append('\n');
                    sb.append('\n');
                    totalUsed += usage.getUsed();
                    totalCommitted += usage.getCommitted();
                    totalMax += usage.getMax();
                }
            }

            if (sb.length() == 0) {
                sb.append("No metaspace memory pools found.\n");
            } else {
                sb.append("--- TOTAL ---\n");
                sb.append("  used     : ").append(formatBytes(totalUsed)).append('\n');
                sb.append("  committed: ").append(formatBytes(totalCommitted)).append('\n');
                sb.append("  max      : ").append(formatBytes(totalMax)).append('\n');
            }

            process.write(sb.toString());
        } catch (Exception e) {
            logger.error("classloader-metaspace error", e);
            process.write("Error: " + e.getMessage() + "\n");
        } finally {
            process.end();
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) return "unlimited";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        return String.format("%.2f GB", mb / 1024.0);
    }

    private static String formatPercent(MemoryUsage usage) {
        if (usage.getMax() <= 0) return "N/A";
        return String.format("%.1f%%", 100.0 * usage.getUsed() / usage.getMax());
    }
}
