package com.shanshui.performance.crash

import java.util.concurrent.atomic.AtomicBoolean

/** 崩溃处理器和后台上传共享同一互斥门，不等待另一个上传完成。 */
internal class CrashFlushGate {
    /** 只有持有门的调用方可以执行上传。 */
    private val active = AtomicBoolean(false)

    /** 尝试立即执行一次上传，异常时也释放门。 */
    fun runIfAvailable(action: () -> Unit): Boolean {
        if (!active.compareAndSet(false, true)) return false
        try {
            action()
            return true
        } finally {
            active.set(false)
        }
    }
}
