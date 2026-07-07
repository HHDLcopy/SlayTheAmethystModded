package io.stamethyst.arthas;

public class ProcFSBridge {
    private static volatile boolean loaded = false;
    private static volatile String loadError = null;
    private static final String SO_PATH =
        "/data/data/io.stamethyst/files/libprocfs_cpu.so";

    public static synchronized void ensureLoaded() {
        if (loaded) return;
        try {
            System.load(SO_PATH);
            loaded = true;
        } catch (Throwable t) {
            loadError = t.toString();
            ArthasCommandBridge.log("PROCFS_LOAD FAILED: " + t);
        }
    }

    public static boolean isLoaded() { return loaded; }
    public static String getLoadError() { return loadError; }

    public static native long getTaskCpuTime(int tid);
    public static native long getClkTck();
    public static native int getCurrentTid();
    public static native void getAllTaskCpuTimes(int[] tids, long[] cpuTimes);
}
