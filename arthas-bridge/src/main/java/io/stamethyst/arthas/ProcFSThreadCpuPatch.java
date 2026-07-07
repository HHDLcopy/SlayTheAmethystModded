package io.stamethyst.arthas;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

public class ProcFSThreadCpuPatch {

    private static final String SAMPLER_CLASS =
        "com.taobao.arthas.core.command.monitor200.ThreadSampler";
    private static final String MXBEAN_FIELD = "threadMXBean";

    public static void install() {
        if (!ProcFSBridge.isLoaded()) {
            ArthasCommandBridge.log("PROCFS_PATCH SKIP: so not loaded");
            return;
        }

        try {
            Map<Long, Integer> jvmToKernel = buildTidMapping();
            ArthasCommandBridge.log("PROCFS_TIDMAP: " + jvmToKernel.size() + " threads");

            Class<?> samplerClass = Class.forName(SAMPLER_CLASS);
            Field field = samplerClass.getDeclaredField(MXBEAN_FIELD);
            field.setAccessible(true);
            Object realBean = field.get(null);

            Object proxy = Proxy.newProxyInstance(
                ThreadMXBean.class.getClassLoader(),
                new Class<?>[]{ThreadMXBean.class},
                (obj, method, args) -> {
                    if ("getThreadCpuTime".equals(method.getName())
                            && args != null && args.length == 1 && args[0] instanceof Long) {
                        long result = (Long) method.invoke(realBean, args);
                        if (result <= 0) {
                            long jvmTid = (Long) args[0];
                            Integer ktid = jvmToKernel.get(jvmTid);
                            if (ktid != null) {
                                long procfsJiffies = ProcFSBridge.getTaskCpuTime(ktid);
                                if (procfsJiffies >= 0) {
                                    result = procfsJiffies * 10_000_000L;
                                }
                            }
                        }
                        return result;
                    }
                    return method.invoke(realBean, args);
                });

            field.set(null, proxy);
            ArthasCommandBridge.log("PROCFS_PATCH OK: ThreadMXBean wrapped");
        } catch (Throwable t) {
            ArthasCommandBridge.log("PROCFS_PATCH FAIL: " + t);
        }
    }

    public static Map<Long, Integer> buildTidMapping() {
        Map<Long, Integer> mapping = new HashMap<>();
        ThreadMXBean mxBean = ManagementFactory.getThreadMXBean();

        Map<String, Long> nameToJvmId = new HashMap<>();
        for (long tid : mxBean.getAllThreadIds()) {
            ThreadInfo info = mxBean.getThreadInfo(tid);
            if (info != null) {
                nameToJvmId.put(info.getThreadName(), tid);
            }
        }

        File taskDir = new File("/proc/self/task");
        File[] entries = taskDir.listFiles();
        if (entries == null) return mapping;

        for (File entry : entries) {
            try {
                int ktid = Integer.parseInt(entry.getName());
                long cpu = ProcFSBridge.getTaskCpuTime(ktid);
                if (cpu < 0) continue;

                String procName = readProcComm(ktid);
                if (procName == null) continue;

                Long jvmId = nameToJvmId.get(procName);
                if (jvmId == null) {
                    for (Map.Entry<String, Long> e : nameToJvmId.entrySet()) {
                        if (e.getKey().startsWith(procName) || procName.equals(
                                e.getKey().substring(0,
                                    Math.min(15, e.getKey().length())))) {
                            jvmId = e.getValue();
                            break;
                        }
                    }
                }
                if (jvmId != null) {
                    mapping.put(jvmId, ktid);
                }
            } catch (NumberFormatException ignored) {}
        }
        return mapping;
    }

    private static String readProcComm(int tid) {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader("/proc/self/task/" + tid + "/stat"));
            String line = r.readLine();
            r.close();
            if (line == null) return null;
            int start = line.indexOf('(');
            int end = line.lastIndexOf(')');
            if (start >= 0 && end > start) {
                return line.substring(start + 1, end);
            }
        } catch (Exception ignored) {}
        return null;
    }
}
