package io.stamethyst.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

final class BootBridgeConsoleBridge {
    private static final String PROP_SUPPRESS_STARTUP_NOISE = "amethyst.boot_bridge.suppress_startup_noise";

    private BootBridgeConsoleBridge() {
    }

    static void install(BootBridgeReporter reporter) {
        try {
            PrintStream out = System.out;
            if (out != null) {
                System.setOut(new PrintStream(wrapOutputStream(out, false, reporter), true, "UTF-8"));
            }
        } catch (Throwable ignored) {
        }
        try {
            PrintStream err = System.err;
            if (err != null) {
                System.setErr(new PrintStream(wrapOutputStream(err, true, reporter), true, "UTF-8"));
            }
        } catch (Throwable ignored) {
        }
    }

    static OutputStream wrapOutputStream(OutputStream delegate, boolean isErrorStream, BootBridgeReporter reporter) {
        return new BridgeLineOutputStream(delegate, isErrorStream, reporter);
    }

    private static void onConsoleLine(String rawLine, boolean isErrorStream, BootBridgeReporter reporter) {
        String line = BootBridgePhaseMapper.normalize(rawLine);
        if (line.isEmpty()) {
            return;
        }

        if (isErrorStream && line.startsWith("ERROR:")) {
            reporter.fail(BootBridgePhaseMapper.encodeConsoleError(line));
            return;
        }

        BootBridgePhaseMapper.PhaseMatch match = BootBridgePhaseMapper.matchPhase(line);
        if (match != null) {
            reporter.phase(match.progress, match.message);
        }
        if (BootBridgePhaseMapper.isReadyConsoleLine(line)) {
            reporter.markConsoleReadyHint();
        }
    }

    static boolean shouldSuppressStartupNoiseLine(String line) {
        if (line == null) {
            return false;
        }
        String value = BootBridgePhaseMapper.normalize(line);
        if (value.isEmpty()) {
            return false;
        }
        if (value.startsWith("HermitMod | Loading Texture: ")) {
            return true;
        }
        if (value.startsWith("Encountered non-zero byte at ")
                && value.endsWith(", custom cursor is not empty!")) {
            return true;
        }
        return isBareCardClassTrace(value);
    }

    private static boolean isBareCardClassTrace(String line) {
        if (!line.contains(".cards.")) {
            return false;
        }
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (Character.isWhitespace(ch) || ch == ':' || ch == '[' || ch == ']') {
                return false;
            }
        }
        return true;
    }

    private static boolean isStartupNoiseSuppressionEnabled() {
        String value = System.getProperty(PROP_SUPPRESS_STARTUP_NOISE, "true");
        return !"false".equalsIgnoreCase(value.trim())
                && !"0".equals(value.trim())
                && !"off".equalsIgnoreCase(value.trim())
                && !"no".equalsIgnoreCase(value.trim());
    }

    private static final class BridgeLineOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final boolean isErrorStream;
        private final BootBridgeReporter reporter;
        private final boolean suppressStartupNoise;
        private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream(256);

        private BridgeLineOutputStream(OutputStream delegate, boolean isErrorStream, BootBridgeReporter reporter) {
            this.delegate = delegate;
            this.isErrorStream = isErrorStream;
            this.reporter = reporter;
            this.suppressStartupNoise = !isErrorStream && isStartupNoiseSuppressionEnabled();
        }

        @Override
        public synchronized void write(int b) throws IOException {
            if (!suppressStartupNoise) {
                delegate.write(b);
            }
            accept((byte) b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            if (!suppressStartupNoise) {
                delegate.write(b, off, len);
            }
            int end = off + len;
            for (int i = off; i < end; i++) {
                accept(b[i]);
            }
        }

        @Override
        public synchronized void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public synchronized void close() throws IOException {
            flushBufferedLine(false);
            delegate.close();
        }

        private void accept(byte value) throws IOException {
            if (value == '\n') {
                flushBufferedLine(true);
                return;
            }
            if (value != '\r') {
                lineBuffer.write(value);
            }
        }

        private void flushBufferedLine(boolean appendLineBreak) throws IOException {
            if (lineBuffer.size() <= 0) {
                return;
            }
            String line = new String(lineBuffer.toByteArray(), StandardCharsets.UTF_8);
            lineBuffer.reset();
            onConsoleLine(line, isErrorStream, reporter);
            if (suppressStartupNoise && shouldSuppressStartupNoiseLine(line)) {
                return;
            }
            if (suppressStartupNoise) {
                delegate.write(line.getBytes(StandardCharsets.UTF_8));
                if (appendLineBreak) {
                    delegate.write('\n');
                }
            }
        }
    }
}
