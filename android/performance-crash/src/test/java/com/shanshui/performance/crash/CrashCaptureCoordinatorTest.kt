package com.shanshui.performance.crash

import com.shanshui.performance.network.CrashDiagnostics
import com.shanshui.performance.network.CrashEvent
import com.shanshui.performance.network.CrashPayload
import com.shanshui.performance.network.CrashedThread
import com.shanshui.performance.network.CrashProcess
import com.shanshui.performance.network.OtherThreads
import com.shanshui.performance.network.StackFrame
import com.shanshui.performance.network.ThrowableNode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 验证核心先保存、冻结时序和迟到工作者隔离。 */
class CrashCaptureCoordinatorTest {
    /** 每个测试使用独立队列。 */ @get:Rule val temporaryFolder = TemporaryFolder()

    /** 工作线程尚未结束时事件不可上传，完成后只冻结一次。 */
    @Test
    fun hidesCapturingEventUntilDiagnosticsAreFrozen() {
        val queue = FileCrashQueue(temporaryFolder.newFolder("capture-wait"))
        val collector = BlockingCollector()
        val worker = Executors.newSingleThreadExecutor()
        val caller = Executors.newSingleThreadExecutor()
        try {
            val coordinator = CrashCaptureCoordinator(queue, CrashEventBudget(16 * 1024), collector, worker, waitMillis = 1000)
            val result = caller.submit<Boolean> { coordinator.capture(event(), Thread.currentThread(), 42, RuntimeException()) }
            assertTrue(collector.entered.await(1, TimeUnit.SECONDS))
            assertEquals(0, queue.count())
            assertTrue(queue.peekBatch(1).isEmpty())
            collector.release.countDown()
            assertTrue(result.get(1, TimeUnit.SECONDS))
            val saved = queue.peekBatch(1).single().record.event
            assertEquals("ok", saved.crash!!.diagnostics!!.otherThreads!!.status)
            assertEquals(42, saved.crash!!.diagnostics!!.crashedThread!!.systemTid)
            assertEquals(1, queue.count())
        } finally {
            collector.release.countDown()
            caller.shutdownNow()
            worker.shutdownNow()
        }
    }

    /** 超时后即使工作者忽略中断并迟到，也不能覆盖已冻结事件。 */
    @Test
    fun lateResultCannotModifyFrozenEvent() {
        val queue = FileCrashQueue(temporaryFolder.newFolder("capture-timeout"))
        val collector = BlockingCollector()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val coordinator = CrashCaptureCoordinator(queue, CrashEventBudget(16 * 1024), collector, worker, waitMillis = 60)
            assertTrue(coordinator.capture(event(), Thread.currentThread(), 42, RuntimeException()))
            val frozen = queue.peekBatch(1).single().record.event
            assertEquals("timeout", frozen.crash!!.diagnostics!!.otherThreads!!.reason)
            assertTrue(collector.cancelled)
            collector.release.countDown()
            assertTrue(collector.finished.await(1, TimeUnit.SECONDS))
            assertEquals(frozen, queue.peekBatch(1).single().record.event)
            assertEquals(1, queue.count())
        } finally {
            collector.release.countDown()
            worker.shutdownNow()
        }
    }

    /** 异常原因链含 OOM 时向采集器传递跳过高分配工作的标志。 */
    @Test
    fun detectsOomInCauseChain() {
        val queue = FileCrashQueue(temporaryFolder.newFolder("capture-oom"))
        val collector = BlockingCollector().apply { release.countDown() }
        val worker = Executors.newSingleThreadExecutor()
        try {
            val coordinator = CrashCaptureCoordinator(queue, CrashEventBudget(16 * 1024), collector, worker, waitMillis = 1000)
            assertTrue(coordinator.capture(event(), Thread.currentThread(), 42, RuntimeException(OutOfMemoryError())))
            assertTrue(collector.skipExpensive)
            assertFalse(collector.cancelled)
        } finally {
            worker.shutdownNow()
        }
    }

    /** 只使用合成异常及匿名身份。 */
    private fun event(): CrashEvent = CrashEvent(
        schemaVersion = 2, eventId = "capture-event", eventType = "crash", occurredAt = 1,
        sessionId = "session", processId = "11111111-1111-4111-8111-111111111111", anonymousDeviceId = "device",
        packageName = "com.example.app", appVersion = "1", versionCode = 1, buildId = "build", environment = "test",
        channel = "test", osVersion = "35", deviceModel = "synthetic",
        crash = CrashPayload(throwableChain = listOf(ThrowableNode("E", frames = listOf(StackFrame("A", "run"))))),
    )

    /** 模拟可忽略中断的迟到工作者。 */
    private class BlockingCollector : CrashDiagnosticsCapture {
        /** 工作已开始。 */ val entered = CountDownLatch(1)
        /** 允许工作结束。 */ val release = CountDownLatch(1)
        /** 工作已经结束。 */ val finished = CountDownLatch(1)
        /** 是否收到取消。 */ @Volatile var cancelled = false
        /** 是否应跳过昂贵采集。 */ @Volatile var skipExpensive = false
        /** 返回处理器传入的线程与系统 TID。 */
        override fun captureCrashed(thread: Thread, tid: Int?) = CrashedThread(
            status = "ok", javaId = thread.id.toString(), name = thread.name, state = thread.state.name, systemTid = tid,
        )
        /** 返回发生时冻结的进程号。 */
        override fun captureProcess() = CrashProcess(status = "ok", pid = 123, name = "com.example")
        /** 等待测试放行；被中断也不会自行写入队列。 */
        override fun collect(thread: Thread, tid: Int?, deadlineNanos: Long, skipExpensive: Boolean): CrashDiagnostics {
            this.skipExpensive = skipExpensive
            entered.countDown()
            while (release.count > 0) {
                try { release.await() } catch (_: InterruptedException) { }
            }
            finished.countDown()
            return CrashDiagnostics(otherThreads = OtherThreads(status = "ok"))
        }
        /** 记录取消请求。 */
        override fun cancel() { cancelled = true }
    }
}
