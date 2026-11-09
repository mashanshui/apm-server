package com.shanshui.performance.crash

import java.util.concurrent.atomic.AtomicBoolean

/** 隔离异常入口的单次捕获门禁和最终委托行为。 */
internal class CrashExceptionHandler(
    /** 捕获并尝试上传当前异常。 */ private val capture: (Thread, Int?, Throwable) -> Unit,
    /** 将原线程和异常交给上一个处理器。 */ private val delegate: (Thread, Throwable) -> Unit,
    /** 在发生异常的线程取得系统 TID。 */ private val currentTid: () -> Int?,
) : Thread.UncaughtExceptionHandler {
    /** 同一进程只启动一次崩溃采集。 */ private val handling = AtomicBoolean(false)

    /** 无论捕获、线程号读取或日志失败，都只委托本次异常一次。 */
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val systemTid = runCatching { currentTid() }.getOrNull()
        try {
            if (handling.compareAndSet(false, true)) capture(thread, systemTid, throwable)
        } finally {
            delegate(thread, throwable)
        }
    }
}
