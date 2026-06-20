package io.stamethyst.agent.monitors.impl;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Thread-safe single-consumer command queue bridging the agent TCP thread
 * (producer) and the game render thread (consumer via AutoplayDriver tick).
 */
public final class CommandQueue {

    private static final BlockingQueue<QueuedPlayCommand> INSTANCE = new LinkedBlockingQueue<QueuedPlayCommand>();

    private CommandQueue() {}

    public static void enqueue(PlayCommand command, String paramsJson) {
        INSTANCE.offer(new QueuedPlayCommand(command, paramsJson));
    }

    public static QueuedPlayCommand poll(int timeoutMs) {
        try {
            return INSTANCE.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public static QueuedPlayCommand pollNow() {
        return INSTANCE.poll();
    }

    public static void clear() {
        INSTANCE.clear();
    }

    public static int size() {
        return INSTANCE.size();
    }

    public static class QueuedPlayCommand {
        public final PlayCommand command;
        public final String paramsJson;

        QueuedPlayCommand(PlayCommand command, String paramsJson) {
            this.command = command;
            this.paramsJson = paramsJson;
        }
    }
}
