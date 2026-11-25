/*
 * Tencent is pleased to support the open source community by making wechat-matrix available.
 * Copyright (C) 2021 THL A29 Limited, a Tencent company. All rights reserved.
 * Licensed under the BSD 3-Clause License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "MatrixTracer.h"
#include "AnrDumper.h"
#include "TraceCaptureGate.h"
#include "nativehelper/managed_jnienv.h"

#include <android/log.h>
#include <bytehook.h>
#include <jni.h>
#include <sys/socket.h>
#include <sys/system_properties.h>
#include <unistd.h>
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <algorithm>
#include <atomic>
#include <fstream>
#include <mutex>
#include <optional>
#include <string>

#define PROP_VALUE_MAX 92
#define PROP_SDK_NAME "ro.build.version.sdk"
#define HOOK_CONNECT_PATH "/dev/socket/tombstoned_java_trace"
#define HOOK_OPEN_PATH "/data/anr/traces.txt"

using namespace MatrixTracer;

/** Matrix 原有 ANR 信号处理对象。 */
static std::optional<AnrDumper> sAnrDumper;
/** 是否命中 Matrix 原有的 trace 写入线程。 */
static bool isTraceWrite = false;
/** 当前手动 trace 标志，行为与 Matrix 相同。 */
static std::atomic<bool> fromMyPrintTrace{false};
/** Matrix 原有 hook 活动标志。 */
static bool isHooking = false;
/** 同次转储的归属门禁。 */
static TraceCaptureGate captureGate;
/** 保护 hook 活动状态及当前票据。 */
static std::mutex traceMutex;
/** 串行化 ByteHook 注册过程。 */
static std::mutex hookMutex;
/** 当前活动 hook 对应的捕获票据。 */
static uint64_t activeTicket = 0;
/** 当前活动 hook 对应的外部采集身份。 */
static std::string activeCaptureId;
/** 当前活动 hook 是否来自手动转储。 */
static bool activeManual = false;
/** 已开始封存但尚未结束 Java 回调的 trace 数量。 */
static unsigned activeTraceCallbacks = 0;
/** 本地单份首次写入的上限。 */
static constexpr size_t MAX_TRACE_BYTES = 2 * 1024 * 1024;
/** Matrix 指定的 ANR trace 文件。 */
static std::string anrTracePathString;
/** Matrix 指定的手动 trace 文件。 */
static std::string printTracePathString;
/** Matrix 识别出的 Signal Catcher 线程号。 */
static int signalCatcherTid;
/** ByteHook 已注册任务需保留；Matrix 不主动卸载这些 PLT hook。 */
static bytehook_stub_t pathHookStub;
/** ByteHook 的 write 任务。 */
static bytehook_stub_t writeHookStub;

/** JNI 的 Matrix 回调引用。 */
static struct StacktraceJNI {
    /** Java Matrix 采集器全局类引用。 */
    jclass AnrDetective;
    /** SIGQUIT Java 采样回调方法。 */
    jmethodID AnrDetector_onANRDumped;
    /** ANR trace Java 回调方法。 */
    jmethodID AnrDetector_onANRDumpTrace;
    /** 手动 trace Java 回调方法。 */
    jmethodID AnrDetector_onPrintTrace;
    /** debugger Java 回调方法。 */
    jmethodID AnrDetector_onNativeBacktraceDumped;
} gJ;

/** 将首次命中的有界字节先写临时文件，再封存到独立路径。 */
static bool writeAnr(const void* content, size_t count, const std::string &filePath) {
    /** 尚未发布的临时路径。 */
    std::string temporary = filePath + ".tmp";
    /** 二进制输出避免读取超过系统 write 的实际长度。 */
    std::ofstream outfile(temporary, std::ios::binary | std::ios::trunc);
    if (!outfile) return false;
    outfile.write(static_cast<const char*>(content), static_cast<std::streamsize>(count));
    outfile.close();
    if (!outfile || std::rename(temporary.c_str(), filePath.c_str()) != 0) {
        std::remove(temporary.c_str());
        return false;
    }
    return true;
}

/** 按 Matrix 原逻辑识别 tombstoned 连接线程。 */
int my_connect(int __fd, const struct sockaddr* __addr, socklen_t __addr_length) {
    BYTEHOOK_STACK_SCOPE();
    if (__addr!= nullptr) {
        if (strcmp(__addr->sa_data, HOOK_CONNECT_PATH) == 0) {
            std::lock_guard<std::mutex> lock(traceMutex);
            if (isHooking) {
                signalCatcherTid = gettid();
                isTraceWrite = true;
            }
        }
    }
    return BYTEHOOK_CALL_PREV(my_connect, __fd, __addr, __addr_length);
}


/** 按 Matrix 原逻辑识别旧版 ANR 文件打开线程。 */
int my_open(const char *pathname, int flags, mode_t mode) {
    BYTEHOOK_STACK_SCOPE();
    if (pathname!= nullptr) {
        if (strcmp(pathname, HOOK_OPEN_PATH) == 0) {
            std::lock_guard<std::mutex> lock(traceMutex);
            if (isHooking) {
                signalCatcherTid = gettid();
                isTraceWrite = true;
            }
        }
    }
    return BYTEHOOK_CALL_PREV(my_open, pathname, flags, mode);
}

/** 按 Matrix 原顺序截取首次写入并调用原 write。 */
ssize_t my_write(int fd, const void* const buf, size_t count) {
    BYTEHOOK_STACK_SCOPE();
    /** 已确认归属的路径、来源与采集身份。 */
    std::string targetFilePath;
    std::string captureId;
    bool manual = false;
    {
        std::lock_guard<std::mutex> lock(traceMutex);
        if (isTraceWrite && gettid() == signalCatcherTid) {
            isTraceWrite = false;
            signalCatcherTid = 0;
            /** 重叠时消费写入但不发布无法确认归属的文件。 */
            bool matched = captureGate.observe(activeTicket);
            isHooking = captureGate.hasPending();
            if (matched && buf != nullptr) {
                manual = activeManual;
                captureId = activeCaptureId;
                const std::string& base = manual ? printTracePathString : anrTracePathString;
                if (!base.empty() && (manual || !captureId.empty())) {
                    targetFilePath = base + "." + (manual ? std::to_string(activeTicket) : captureId);
                    activeTraceCallbacks++;
                }
            }
        }
    }
    if (!targetFilePath.empty()) {
        /** 保留首次 write 的实际字节，超过本地预算时显式标记。 */
        size_t savedBytes = std::min(count, MAX_TRACE_BYTES);
        if (writeAnr(buf, savedBytes, targetFilePath)) {
            if (manual) printTraceCallback(targetFilePath, savedBytes < count);
            else anrDumpTraceCallback(captureId, targetFilePath, savedBytes < count);
        } else {
            __android_log_print(ANDROID_LOG_WARN, "PerformanceAnr", "Matrix trace 封存失败");
        }
        /** 发布和 Java 回调结束后才允许重新装配。 */
        std::lock_guard<std::mutex> lock(traceMutex);
        activeTraceCallbacks--;
    }
    return BYTEHOOK_CALL_PREV(my_write, fd, buf, count);
}

/** 调用 Matrix Java ANR 采样回调。 */
std::string anrDumpCallback() {
    /** 当前回调线程的 JNI 环境。 */
    JNIEnv *env = JniInvocation::getEnv();
    if (!env) return "";
    jstring result = static_cast<jstring>(env->CallStaticObjectMethod(gJ.AnrDetective, gJ.AnrDetector_onANRDumped));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_WARN, "PerformanceAnr", "Matrix Java 采样回调失败");
        return "";
    }
    if (!result) return "";
    const char* value = env->GetStringUTFChars(result, nullptr);
    std::string id = value == nullptr ? "" : value;
    if (value != nullptr) env->ReleaseStringUTFChars(result, value);
    env->DeleteLocalRef(result);
    return id;
}

/** 调用 Matrix Java ANR trace 回调。 */
bool anrDumpTraceCallback(const std::string& captureId, const std::string& path, bool truncated) {
    /** 当前回调线程的 JNI 环境。 */
    JNIEnv *env = JniInvocation::getEnv();
    if (!env) return false;
    jstring id = env->NewStringUTF(captureId.c_str());
    jstring file = env->NewStringUTF(path.c_str());
    env->CallStaticVoidMethod(gJ.AnrDetective, gJ.AnrDetector_onANRDumpTrace, id, file, static_cast<jboolean>(truncated));
    env->DeleteLocalRef(id);
    env->DeleteLocalRef(file);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_WARN, "PerformanceAnr", "Matrix trace 回调失败");
        return false;
    }
    return true;
}

/** 调用 Matrix Java debugger 分支回调。 */
bool nativeBacktraceDumpCallback() {
    /** 当前回调线程的 JNI 环境。 */
    JNIEnv *env = JniInvocation::getEnv();
    if (!env) return false;
    env->CallStaticVoidMethod(gJ.AnrDetective, gJ.AnrDetector_onNativeBacktraceDumped);
    /** Matrix 原有的临时字符串。 */
    std::string to;
    /** Matrix 原有文件输出流。 */
    std::ofstream outfile;
    return true;
}


/** 调用 Matrix Java 手动 trace 回调。 */
bool printTraceCallback(const std::string& path, bool truncated) {
    /** 当前回调线程的 JNI 环境。 */
    JNIEnv *env = JniInvocation::getEnv();
    if (!env) return false;
    jstring file = env->NewStringUTF(path.c_str());
    env->CallStaticVoidMethod(gJ.AnrDetective, gJ.AnrDetector_onPrintTrace, file, static_cast<jboolean>(truncated));
    env->DeleteLocalRef(file);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_WARN, "PerformanceAnr", "Matrix 手动 trace 回调失败");
        return false;
    }
    return true;
}

/** 按 Matrix 原方式读取 Android API 版本。 */
int getApiLevel() {
    /** Android 版本属性缓冲区。 */
    char buf[PROP_VALUE_MAX];
    /** 系统属性实际长度。 */
    int len = __system_property_get(PROP_SDK_NAME, buf);
    if (len <= 0)
        return 0;

    return atoi(buf);
}


/** 匹配 Matrix xHook 正则中的调用方 SO 后缀，包括同名的多个映射。 */
static bool matchesMatrixCaller(const char *callerPath, void *argument) {
    /** 按 Matrix 正则选择的调用方 SO 后缀。 */
    const char *suffix = static_cast<const char *>(argument);
    /** 当前调用方路径长度。 */
    const size_t pathLength = strlen(callerPath);
    /** 目标 SO 后缀长度。 */
    const size_t suffixLength = strlen(suffix);
    return pathLength >= suffixLength && strcmp(callerPath + pathLength - suffixLength, suffix) == 0;
}

/** 记录 ByteHook 的安装结果，不改变 Matrix 的信号与采集顺序。 */
static void onHooked(bytehook_stub_t, int status, const char *, const char *, void *, void *, void *) {
    if (status == BYTEHOOK_STATUS_CODE_OK) {
        __android_log_print(ANDROID_LOG_INFO, "PerformanceAnr", "Matrix trace PLT hook 已安装");
    } else {
        __android_log_print(ANDROID_LOG_WARN, "PerformanceAnr", "Matrix trace PLT hook 状态：%d", status);
    }
}

/** 按 Matrix 的 API 版本分支和时机使用 ByteHook 注册同一组 PLT hook。 */
uint64_t beginTraceCapture() {
    std::lock_guard<std::mutex> lock(traceMutex);
    return captureGate.begin();
}

/** 消费显式发起的手动 trace 请求。 */
bool takePrintTraceRequest() {
    return fromMyPrintTrace.exchange(false);
}

/** 按 Matrix 原调用方安装 hook，并固定当前唯一捕获的身份。 */
void hookAnrTraceWrite(bool isSiUser, uint64_t ticket, const std::string& captureId) {
    /** Matrix 原分支使用的 Android API 版本。 */
    int apiLevel = getApiLevel();
    if (apiLevel < 19) {
        return;
    }

    std::lock_guard<std::mutex> registration(hookMutex);
    {
        std::lock_guard<std::mutex> lock(traceMutex);
        if (isHooking) return;
        isHooking = true;
        activeTicket = ticket;
        activeCaptureId = captureId;
        activeManual = isSiUser;
    }

    // ByteHook 将任务保留到进程退出，对应 Matrix 只清除 isHooking 而不卸载 xHook。
    if (!pathHookStub) {
        if (apiLevel >= 27) {
            pathHookStub = bytehook_hook_partial(matchesMatrixCaller, const_cast<char *>("libcutils.so"),
                                                nullptr, "connect", reinterpret_cast<void *>(my_connect), onHooked, nullptr);
        } else {
            pathHookStub = bytehook_hook_partial(matchesMatrixCaller, const_cast<char *>("libart.so"),
                                                nullptr, "open", reinterpret_cast<void *>(my_open), onHooked, nullptr);
        }
    }

    if (!writeHookStub) {
        /** Matrix 原分支选择的 write 调用方。 */
        const char *caller;
        if (apiLevel >= 30 || apiLevel == 25 || apiLevel == 24) {
            caller = "libc.so";
        } else if (apiLevel == 29) {
            caller = "libbase.so";
        } else {
            caller = "libart.so";
        }
        writeHookStub = bytehook_hook_partial(matchesMatrixCaller, const_cast<char *>(caller),
                                             nullptr, "write", reinterpret_cast<void *>(my_write), onHooked, nullptr);
    }
    if (!pathHookStub || !writeHookStub) {
        __android_log_print(ANDROID_LOG_WARN, "PerformanceAnr", "Matrix trace PLT hook 任务注册失败");
    }
}

/** 按 Matrix 原实现创建原生信号采集器。 */
static void nativeInitSignalAnrDetective(JNIEnv *env, jclass, jstring anrTracePath, jstring printTracePath) {
    /** Matrix 外部 ANR trace 路径字符指针。 */
    const char* anrTracePathChar = env->GetStringUTFChars(anrTracePath, nullptr);
    /** Matrix 手动 trace 路径字符指针。 */
    const char* printTracePathChar = env->GetStringUTFChars(printTracePath, nullptr);
    anrTracePathString = std::string(anrTracePathChar);
    printTracePathString = std::string(printTracePathChar);
    sAnrDumper.emplace(anrTracePathString.c_str(), printTracePathString.c_str());
    env->ReleaseStringUTFChars(anrTracePath, anrTracePathChar);
    env->ReleaseStringUTFChars(printTracePath, printTracePathChar);
}

/** 按 Matrix 原实现销毁原生信号采集器。 */
static void nativeFreeSignalAnrDetective(JNIEnv *env, jclass) {
    sAnrDumper.reset();
}

/** 旧信号和 trace 回调结束后允许重新启动，未到达的旧写入不再关联。 */
static jboolean nativeCanRestart(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(traceMutex);
    if (sAnrDumper.has_value() || MatrixTracer::hasSignalCallbacks() || activeTraceCallbacks != 0) return false;
    // 无法确认旧写入是否仍会到达；保留待处理计数并丢弃关联，避免新旧会话串档。
    if (captureGate.hasPending()) {
        captureGate.abandonAssociations();
        __android_log_print(ANDROID_LOG_WARN, "PerformanceAnr", "旧 trace 尚未结束，重启后暂不关联不确定片段");
    }
    return true;
}

/** 按 Matrix 原实现发送自发 SIGQUIT。 */
static void nativePrintTrace(JNIEnv *, jclass) {
    fromMyPrintTrace.store(true);
    kill(getpid(), SIGQUIT);
}

/** 注册 Matrix ANR 所需的 JNI 方法和静态回调。 */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JniInvocation::init(vm);
    /** JNI 注册环境。 */
    JNIEnv *env;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    /** 迁移后的 Matrix Java 采集器类。 */
    jclass anrClass = env->FindClass("com/shanshui/performance/anr/internal/SignalAnrTracer");
    if (!anrClass) {
        return JNI_ERR;
    }
    gJ.AnrDetective = static_cast<jclass>(env->NewGlobalRef(anrClass));
    gJ.AnrDetector_onANRDumped = env->GetStaticMethodID(anrClass, "onANRDumped", "()Ljava/lang/String;");
    gJ.AnrDetector_onANRDumpTrace = env->GetStaticMethodID(anrClass, "onANRDumpTrace", "(Ljava/lang/String;Ljava/lang/String;Z)V");
    gJ.AnrDetector_onPrintTrace = env->GetStaticMethodID(anrClass, "onPrintTrace", "(Ljava/lang/String;Z)V");
    gJ.AnrDetector_onNativeBacktraceDumped = env->GetStaticMethodID(anrClass, "onNativeBacktraceDumped", "()V");

    /** 需要注册的 Matrix 原生方法。 */
    const JNINativeMethod methods[] = {
        {const_cast<char *>("nativeInitSignalAnrDetective"), const_cast<char *>("(Ljava/lang/String;Ljava/lang/String;)V"), reinterpret_cast<void *>(nativeInitSignalAnrDetective)},
        {const_cast<char *>("nativeFreeSignalAnrDetective"), const_cast<char *>("()V"), reinterpret_cast<void *>(nativeFreeSignalAnrDetective)},
        {const_cast<char *>("nativeCanRestart"), const_cast<char *>("()Z"), reinterpret_cast<void *>(nativeCanRestart)},
        {const_cast<char *>("nativePrintTrace"), const_cast<char *>("()V"), reinterpret_cast<void *>(nativePrintTrace)},
    };
    if (env->ExceptionCheck() || env->RegisterNatives(anrClass, methods, sizeof(methods) / sizeof(methods[0])) != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, "PerformanceAnr", "Matrix ANR JNI 注册失败");
        return JNI_ERR;
    }
    env->DeleteLocalRef(anrClass);
    __android_log_print(ANDROID_LOG_INFO, "PerformanceAnr", "Matrix ANR JNI 注册成功");
    return JNI_VERSION_1_6;
}
