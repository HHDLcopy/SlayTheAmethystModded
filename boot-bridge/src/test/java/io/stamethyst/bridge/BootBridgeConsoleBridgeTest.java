package io.stamethyst.bridge;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BootBridgeConsoleBridgeTest {
    @Test
    public void suppressesKnownTextureSpam() {
        assertTrue(BootBridgeConsoleBridge.shouldSuppressStartupNoiseLine(
                "HermitMod | Loading Texture: slimeboundResources/SlimeboundImages/betacards/Strike.png"
        ));
    }

    @Test
    public void suppressesKnownCursorByteSpam() {
        assertTrue(BootBridgeConsoleBridge.shouldSuppressStartupNoiseLine(
                "Encountered non-zero byte at 128, custom cursor is not empty!"
        ));
    }

    @Test
    public void suppressesBareCardClassTrace() {
        assertTrue(BootBridgeConsoleBridge.shouldSuppressStartupNoiseLine(
                "theHexaghost.cards.NightmareStrike"
        ));
    }

    @Test
    public void keepsProgressAndCrashRelevantLines() {
        assertFalse(BootBridgeConsoleBridge.shouldSuppressStartupNoiseLine(
                "06:59:53.647 INFO basemod.BaseMod> begin editing cards"
        ));
        assertFalse(BootBridgeConsoleBridge.shouldSuppressStartupNoiseLine(
                "java.lang.NullPointerException: synthetic test"
        ));
        assertFalse(BootBridgeConsoleBridge.shouldSuppressStartupNoiseLine(
                "at theHexaghost.cards.NightmareStrike.use(NightmareStrike.java:42)"
        ));
    }

    @Test
    public void delayedLineBreakDoesNotConcatenateFlushedLines() throws Exception {
        ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        BootBridgeReporter reporter = new BootBridgeReporter(BootBridgeEventSink.fromSystemProperty());
        OutputStream stream = BootBridgeConsoleBridge.wrapOutputStream(delegate, false, reporter);

        stream.write("alpha".getBytes(StandardCharsets.UTF_8));
        stream.flush();
        stream.write('\n');
        stream.flush();
        stream.write("beta\n".getBytes(StandardCharsets.UTF_8));
        stream.flush();

        assertEquals("alpha\nbeta\n", delegate.toString("UTF-8"));
    }

    @Test
    public void outputWrapperSuppressesNoiseLine() throws Exception {
        ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        BootBridgeReporter reporter = new BootBridgeReporter(BootBridgeEventSink.fromSystemProperty());
        OutputStream stream = BootBridgeConsoleBridge.wrapOutputStream(delegate, false, reporter);

        stream.write("Encountered non-zero byte at 4, custom cursor is not empty!\n".getBytes(StandardCharsets.UTF_8));
        stream.write("07:00:00.000 INFO core.CardCrawlGame> No migration\n".getBytes(StandardCharsets.UTF_8));
        stream.flush();

        assertEquals("07:00:00.000 INFO core.CardCrawlGame> No migration\n", delegate.toString("UTF-8"));
    }
}
