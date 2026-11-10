#include <jni.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>

/** 真实空地址写入，供示例页验证同步 Native 致命信号。 */
__attribute__((noinline)) static void apm_demo_segv(void) {
    volatile int *address = (volatile int *) (uintptr_t) 0;
    *address = 1;
}

/** 不附着 JVM 的独立线程用于验证纯 Native 场景。 */
static void *apm_demo_native_thread(void *unused) {
    (void) unused;
    (void) pthread_setname_np(pthread_self(), "apm-native-demo");
    apm_demo_segv();
    return NULL;
}

/** 在当前 Java 调用线程触发真正的 SIGSEGV。 */
JNIEXPORT void JNICALL
Java_com_shanshui_performance_NativeCrashDemo_nativeSegv(JNIEnv *env, jobject receiver) {
    (void) env;
    (void) receiver;
    apm_demo_segv();
}

/** 在不附着 JVM 的线程触发真正的 SIGSEGV。 */
JNIEXPORT jboolean JNICALL
Java_com_shanshui_performance_NativeCrashDemo_nativeThreadSegv(JNIEnv *env, jobject receiver) {
    (void) env;
    (void) receiver;
    pthread_t thread;
    if (pthread_create(&thread, NULL, apm_demo_native_thread, NULL) != 0) return JNI_FALSE;
    (void) pthread_detach(thread);
    return JNI_TRUE;
}

/** 在当前线程触发真正的 SIGABRT。 */
JNIEXPORT void JNICALL
Java_com_shanshui_performance_NativeCrashDemo_nativeAbort(JNIEnv *env, jobject receiver) {
    (void) env;
    (void) receiver;
    (void) raise(SIGABRT);
}
