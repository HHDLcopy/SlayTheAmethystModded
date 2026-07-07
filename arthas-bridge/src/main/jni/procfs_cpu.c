#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/syscall.h>

static long readTaskCpuJiffies(int tid)
{
    char path[64];
    snprintf(path, sizeof(path), "/proc/self/task/%d/stat", tid);

    FILE *f = fopen(path, "r");
    if (!f) return -1;

    unsigned long utime = 0, stime = 0;
    int field = 1;
    int c;
    int in_paren = 0;
    long result = -1;

    while ((c = fgetc(f)) != EOF) {
        if (c == '(') { in_paren = 1; continue; }
        if (in_paren) { if (c == ')') in_paren = 0; continue; }
        if (c == ' ') {
            field++;
            if (field == 14) {
                if (fscanf(f, "%lu", &utime) != 1) goto out;
            } else if (field == 15) {
                if (fscanf(f, "%lu", &stime) != 1) goto out;
                result = (long)(utime + stime);
                goto out;
            }
        }
    }
out:
    fclose(f);
    return result;
}

JNIEXPORT jint JNICALL
Java_io_stamethyst_arthas_ProcFSBridge_getCurrentTid(
    JNIEnv *_env, jclass _cls)
{
    (void)_env; (void)_cls;
    return (jint)gettid();
}

JNIEXPORT jlong JNICALL
Java_io_stamethyst_arthas_ProcFSBridge_getTaskCpuTime(
    JNIEnv *_env, jclass _cls, jint tid)
{
    (void)_env; (void)_cls;
    return (jlong)readTaskCpuJiffies(tid);
}

JNIEXPORT jlong JNICALL
Java_io_stamethyst_arthas_ProcFSBridge_getClkTck(
    JNIEnv *_env, jclass _cls)
{
    (void)_env; (void)_cls;
    return (jlong)100;
}

JNIEXPORT void JNICALL
Java_io_stamethyst_arthas_ProcFSBridge_getAllTaskCpuTimes(
    JNIEnv *env, jclass _cls, jintArray tids, jlongArray cpuTimes)
{
    (void)_cls;
    jsize len = (*env)->GetArrayLength(env, tids);
    jsize outLen = (*env)->GetArrayLength(env, cpuTimes);
    if (len != outLen) return;

    jint *tidBuf = (*env)->GetIntArrayElements(env, tids, NULL);
    jlong *cpuBuf = (*env)->GetLongArrayElements(env, cpuTimes, NULL);
    if (!tidBuf || !cpuBuf) {
        if (tidBuf) (*env)->ReleaseIntArrayElements(env, tids, tidBuf, JNI_ABORT);
        if (cpuBuf) (*env)->ReleaseLongArrayElements(env, cpuTimes, cpuBuf, JNI_ABORT);
        return;
    }

    for (jsize i = 0; i < len; i++) {
        cpuBuf[i] = (jlong)readTaskCpuJiffies(tidBuf[i]);
    }

    (*env)->ReleaseIntArrayElements(env, tids, tidBuf, JNI_ABORT);
    (*env)->ReleaseLongArrayElements(env, cpuTimes, cpuBuf, 0);
}
