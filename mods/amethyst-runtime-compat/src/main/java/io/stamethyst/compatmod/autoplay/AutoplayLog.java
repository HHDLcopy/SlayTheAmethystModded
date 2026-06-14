package io.stamethyst.compatmod.autoplay;

/**
 * Lightweight logger that prefixes every line so autoplay output is easy to grep out of game logs.
 */
public final class AutoplayLog {
    private static final String PREFIX = "[amethyst-autoplay]";

    private AutoplayLog() {
    }

    public static void info(String message) {
        System.out.println(PREFIX + " " + message);
    }

    public static void debug(String message) {
        if (!AutoplayConfig.isDebugLogEnabled()) {
            return;
        }
        System.out.println(PREFIX + " [debug] " + message);
    }

    public static void warn(String message, Throwable cause) {
        StringBuilder builder = new StringBuilder(PREFIX);
        builder.append(" [warn] ").append(message);
        if (cause != null) {
            builder.append(" reason=").append(cause.getClass().getSimpleName());
            String causeMessage = cause.getMessage();
            if (causeMessage != null && causeMessage.length() > 0) {
                builder.append(": ").append(causeMessage);
            }
        }
        System.out.println(builder.toString());
    }
}
