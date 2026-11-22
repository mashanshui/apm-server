#include <jni.h>
#include <signal.h>
#include <stdint.h>
#include <string.h>

/** 测试用的第三方 SIGQUIT 处理器，不执行额外操作。 */
static void third_party_handler(int signal_number) {
    (void) signal_number;
}

/** 安装测试处理器，模拟其他组件在 Matrix 启动后接管 SIGQUIT。 */
JNIEXPORT jboolean JNICALL
Java_com_shanshui_performance_AnrSignalOwnershipTest_nativeInstallThirdParty(JNIEnv *env, jobject instance) {
    (void) env;
    (void) instance;
    /** 本测试模拟的第三方处理器。 */
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_handler = third_party_handler;
    sigemptyset(&action.sa_mask);
    return sigaction(SIGQUIT, &action, NULL) == 0 ? JNI_TRUE : JNI_FALSE;
}

/** 判断第三方处理器当前是否仍拥有 SIGQUIT。 */
JNIEXPORT jboolean JNICALL
Java_com_shanshui_performance_AnrSignalOwnershipTest_nativeIsThirdPartyInstalled(JNIEnv *env, jobject instance) {
    (void) env;
    (void) instance;
    /** 当前进程的 SIGQUIT 处理器。 */
    struct sigaction action;
    if (sigaction(SIGQUIT, NULL, &action) != 0 || (action.sa_flags & SA_SIGINFO) != 0) return JNI_FALSE;
    return action.sa_handler == third_party_handler ? JNI_TRUE : JNI_FALSE;
}

/** 返回当前处理器地址，仅用于同一进程内比较，不输出地址。 */
JNIEXPORT jlong JNICALL
Java_com_shanshui_performance_AnrSignalOwnershipTest_nativeCurrentHandler(JNIEnv *env, jobject instance) {
    (void) env;
    (void) instance;
    /** 当前进程的 SIGQUIT 处理器。 */
    struct sigaction action;
    if (sigaction(SIGQUIT, NULL, &action) != 0) return 0;
    return (jlong) (uintptr_t) action.sa_sigaction;
}
