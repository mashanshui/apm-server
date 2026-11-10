package com.shanshui.performance

import android.util.Log

/** 仅供示例崩溃页触发真实 Native 信号。 */
internal object NativeCrashDemo {
    /** 记录安全阶段，不记录现场或路径。 */ private const val TAG = "NativeCrashDemo"
    /** 在用户触发示例时才装载演示库。 */
    private val loaded: Boolean by lazy {
        runCatching { System.loadLibrary("apm_crash_demo"); true }
            .onSuccess { Log.i(TAG, "native crash demo library loaded") }
            .onFailure { Log.w(TAG, "native crash demo library unavailable") }.getOrDefault(false)
    }

    /** 当前 Java 线程通过 JNI 触发空地址写入。 */
    fun segv() { if (loaded) nativeSegv() }

    /** 启动纯 Native 线程并触发空地址写入。 */
    fun nativeThread() {
        if (loaded && !nativeThreadSegv()) Log.w(TAG, "native crash demo thread unavailable")
    }

    /** 当前线程触发 SIGABRT。 */
    fun abort() { if (loaded) nativeAbort() }

    /** JNI 同步故障入口。 */ private external fun nativeSegv()
    /** JNI 纯 Native 工作线程入口。 */ private external fun nativeThreadSegv(): Boolean
    /** JNI 终止信号入口。 */ private external fun nativeAbort()
}
