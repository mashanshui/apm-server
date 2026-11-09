package com.shanshui.performance.crash

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** 验证捕获异常时仍将原始线程和异常交给上一个处理器。 */
class CrashExceptionHandlerTest {
    /** 受控 OOM 不能阻止原处理器收到一次委托。 */
    @Test
    fun delegatesOnceWhenCaptureFails() {
        val thread = Thread.currentThread()
        val original = IllegalStateException("synthetic")
        val delegates = AtomicInteger()
        val handler = CrashExceptionHandler(
            capture = { capturedThread, tid, _ ->
                assertSame(thread, capturedThread)
                assertEquals(42, tid)
                throw OutOfMemoryError("controlled")
            },
            delegate = { capturedThread, error ->
                assertSame(thread, capturedThread)
                assertSame(original, error)
                delegates.incrementAndGet()
            },
            currentTid = { 42 },
        )
        assertThrows(OutOfMemoryError::class.java) { handler.uncaughtException(thread, original) }
        assertEquals(1, delegates.get())
    }

    /** 进程处理门禁只采集首次异常，每次回调仍各自委托一次。 */
    @Test
    fun capturesOnceAndDelegatesEachCallback() {
        val captures = AtomicInteger()
        val delegates = AtomicInteger()
        val handler = CrashExceptionHandler(
            capture = { _, _, _ -> captures.incrementAndGet() },
            delegate = { _, _ -> delegates.incrementAndGet() },
            currentTid = { throw IllegalStateException("unavailable") },
        )
        handler.uncaughtException(Thread.currentThread(), RuntimeException())
        handler.uncaughtException(Thread.currentThread(), RuntimeException())
        assertEquals(1, captures.get())
        assertEquals(2, delegates.get())
    }
}
