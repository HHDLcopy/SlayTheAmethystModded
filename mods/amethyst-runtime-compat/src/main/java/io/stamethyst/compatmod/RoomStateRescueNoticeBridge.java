package io.stamethyst.compatmod;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.HashSet;
import java.util.Set;

public final class RoomStateRescueNoticeBridge {
    private static final String REQUEST_PROP = "amethyst.runtime_rescue_toast_request";
    private static final Set<String> LOGGED_KEYS = new HashSet<String>();
    private static boolean toastRequested;

    private RoomStateRescueNoticeBridge() {
    }

    public static void notifyRescue(String key, String detail) {
        String normalizedKey = sanitize(key);
        String normalizedDetail = sanitize(detail);
        logRescueOnce(normalizedKey, normalizedDetail);
        requestToastOnce(normalizedKey, normalizedDetail);
    }

    private static void logRescueOnce(String key, String detail) {
        synchronized (RoomStateRescueNoticeBridge.class) {
            if (!LOGGED_KEYS.add(key)) {
                return;
            }
        }
        System.out.println(
            "[amethyst-runtime-compat] save rescue fallback triggered key="
                + key
                + " detail="
                + detail
        );
    }

    private static void requestToastOnce(String key, String detail) {
        synchronized (RoomStateRescueNoticeBridge.class) {
            if (toastRequested) {
                return;
            }
            toastRequested = true;
        }

        String requestPath = System.getProperty(REQUEST_PROP);
        if (requestPath == null || requestPath.trim().length() == 0) {
            return;
        }
        File requestFile = new File(requestPath.trim());
        File parent = requestFile.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try {
            OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(requestFile, false),
                "UTF-8"
            );
            try {
                writer.write(Long.toString(System.currentTimeMillis()));
                writer.write('\n');
                writer.write(key);
                writer.write('\n');
                writer.write(detail);
                writer.write('\n');
            } finally {
                writer.close();
            }
        } catch (Exception exception) {
            System.out.println(
                "[amethyst-runtime-compat] failed to request save rescue toast: "
                    + RoomContextRescueRuntime.describeThrowable(exception)
            );
        }
    }

    private static String sanitize(String value) {
        if (value == null || value.length() == 0) {
            return "<empty>";
        }
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
