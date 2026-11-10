package com.shanshui.performance.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证线程身份、采集限制和失败状态。 */
class ThreadDiagnosticsCollectorTest {
    /** 按对象排除崩溃线程，同名线程及其他主线程仍保留。 */
    @Test
    fun capturesBackgroundCrashAndSameNamedThreadByIdentity() {
        val crashed = Thread({}, "same")
        val sameName = Thread({}, "same")
        val main = Thread({}, "main")
        val stack = arrayOf(StackTraceElement("com.example.Main", "run", "Main.kt", 12))
        val snapshot = linkedMapOf(crashed to stack, sameName to emptyArray(), main to stack)
        val collector = collector(snapshot = { snapshot }, main = main)
        val result = collector.collect(crashed, systemTid = 1234, deadlineNanos = 100, skipExpensive = false)
        assertEquals(crashed.id.toString(), result.crashedThread.javaId)
        assertEquals(1234, result.crashedThread.systemTid)
        assertFalse(result.crashedThread.mainThread!!)
        assertEquals(2, result.otherThreads.observedCount)
        assertEquals(main.id.toString(), result.otherThreads.threads.first().javaId)
        assertTrue(result.otherThreads.threads.first().mainThread)
        assertEquals(sameName.id.toString(), result.otherThreads.threads.last().javaId)
        assertTrue(result.otherThreads.threads.last().frames.isEmpty())
    }

    /** 数量和每线程帧数分别有限，末尾主线程仍优先保留。 */
    @Test
    fun limitsThreadsAndFramesWhilePrioritizingMain() {
        val crashed = Thread({}, "crashed")
        val otherThreads = List(70) { Thread({}, "worker-$it") }
        val main = otherThreads.last()
        val frames = Array(40) { StackTraceElement("A", "run", "A.kt", it) }
        val snapshot = linkedMapOf<Thread, Array<StackTraceElement>>()
        otherThreads.forEach { snapshot[it] = if (it === main) frames else emptyArray() }
        val result = collector(snapshot = { snapshot }, main = main)
            .collect(crashed, systemTid = 42, deadlineNanos = 100, skipExpensive = false)
        assertTrue(result.otherThreads.truncated)
        assertEquals(70, result.otherThreads.observedCount)
        assertEquals(64, result.otherThreads.threads.size)
        assertTrue(result.otherThreads.threads.first().mainThread)
        assertTrue(result.otherThreads.threads.all { it.frames.size <= 32 })
        assertTrue(result.otherThreads.threads.first().truncated)
        assertEquals(6, result.otherThreads.omittedCount)
    }

    /** 无权限时线程总数为未知，受控 OOM 跳过高分配操作。 */
    @Test
    fun reportsUnknownCountAndSkipsExpensiveWorkForOom() {
        val crashed = Thread({}, "crashed")
        var calls = 0
        val collector = collector(snapshot = { calls++; throw SecurityException() }, main = null)
        val unavailable = collector.collect(crashed, null, 100, false)
        assertEquals("unavailable", unavailable.otherThreads.status)
        assertEquals("permission_denied", unavailable.otherThreads.reason)
        assertNull(unavailable.otherThreads.observedCount)
        val skipped = collector.collect(crashed, null, 100, true)
        assertEquals("skipped", skipped.otherThreads.status)
        assertEquals("memory_pressure", skipped.otherThreads.reason)
        assertEquals(1, calls)
    }

    /** 主线程 Native 崩溃可按 PID/TID 可靠关联 Java，其他同名线程不合并。 */
    @Test
    fun associatesOnlyTheKnownNativeMainThread() {
        val main = Thread({}, "same")
        val worker = Thread({}, "same")
        val frames = arrayOf(StackTraceElement("com.example.Main", "run", "Main.kt", 12))
        val result = collector({ linkedMapOf(worker to emptyArray(), main to frames) }, main).collectNative(42, 42, 100)
        assertEquals(main.id.toString(), result.crashedThread!!.javaId)
        assertEquals(42, result.crashedThread!!.systemTid)
        assertEquals("run", result.javaStack.frames.single().methodName)
        assertEquals(worker.id.toString(), result.otherThreads.threads.single().javaId)
        assertEquals("other", result.otherThreads.threads.single().relationToCrash)
    }

    /** 无法证明 Java 与 Native 线程关联时，包含回调线程在内的所有快照均保留。 */
    @Test
    fun keepsAllJavaThreadsWhenNativeIdentityIsUnknown() {
        val main = Thread({}, "same")
        val callback = Thread({}, "same")
        val result = collector({ linkedMapOf(callback to emptyArray(), main to emptyArray()) }, main)
            .collectNative(42, 123, 100)
        assertNull(result.crashedThread)
        assertEquals("unavailable", result.javaStack.status)
        assertEquals(2, result.otherThreads.observedCount)
        assertEquals(main.id.toString(), result.otherThreads.threads.first().javaId)
        assertEquals(42, result.otherThreads.threads.first().systemTid)
        assertEquals(callback.id.toString(), result.otherThreads.threads.last().javaId)
        assertTrue(result.otherThreads.threads.all { it.relationToCrash == "unknown" })
    }

    /** Native Java 快照受控内存不足或超时时保留独立失败状态和原采集时间。 */
    @Test
    fun reportsNativeMemoryPressureAndDeadlineIndependently() {
        /** 模拟可关联的主线程。 */
        val main = Thread({}, "main")
        /** 受控内存不足时的 Native Java 快照。 */
        val oom = collector({ throw OutOfMemoryError("synthetic") }, main).collectNative(42, 42, 100)
        assertEquals("unavailable", oom.otherThreads.status)
        assertEquals("memory_pressure", oom.otherThreads.reason)
        assertEquals("memory_pressure", oom.javaStack.reason)
        assertEquals(1L, oom.javaStack.capturedAt)
        /** 截止时间已到的 Native Java 快照。 */
        val timeout = collector({ error("不应在截止后读取线程") }, main).collectNative(42, 42, 0)
        assertEquals("timeout", timeout.otherThreads.reason)
        assertEquals("timeout", timeout.javaStack.reason)
    }

    /** 注入稳定时钟和 JVM 快照，避免依赖 Android 运行时。 */
    private fun collector(
        snapshot: () -> Map<Thread, Array<StackTraceElement>>,
        main: Thread?,
    ): ThreadDiagnosticsCollector = ThreadDiagnosticsCollector(
        applicationPackage = "com.example", allThreads = snapshot, mainThread = { main },
        clock = { 1L }, nanoTime = { 0L },
    )
}
