package com.shanshui.performance.crash

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证处理器和后台上传不会并发执行。 */
class CrashFlushGateTest {
    /** 已有上传不等待，持有者结束或抛错之后可以再次获取。 */
    @Test
    fun skipsConcurrentFlushAndReleasesAfterFailure() {
        val gate = CrashFlushGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val worker = Thread {
            gate.runIfAvailable {
                calls.incrementAndGet()
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
        }
        worker.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(gate.runIfAvailable { calls.incrementAndGet() })
        } finally {
            release.countDown()
            worker.join(2000)
        }
        assertEquals(1, calls.get())
        runCatching { gate.runIfAvailable { throw IllegalStateException("synthetic") } }
        assertTrue(gate.runIfAvailable { calls.incrementAndGet() })
        assertEquals(2, calls.get())
    }
}
