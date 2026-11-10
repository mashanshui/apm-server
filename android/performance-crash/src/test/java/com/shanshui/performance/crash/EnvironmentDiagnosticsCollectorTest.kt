package com.shanshui.performance.crash

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证内存口径、FD 竞态和未知生命周期信息。 */
class EnvironmentDiagnosticsCollectorTest {
    /** Runtime 堆和 Debug PSS 独立换算，零值不被当作缺失。 */
    @Test
    fun preservesZeroAndSeparatesHeapFromPss() {
        val source = FakeSource()
        val result = collector(source).collect(100L, false)
        assertEquals(0L, result.memory.javaHeapUsedBytes)
        assertEquals(1000L, result.memory.javaHeapMaxBytes)
        assertEquals(7L * 1024, result.memory.javaHeapPssBytes)
        assertEquals(9L * 1024, result.memory.nativeHeapPssBytes)
        assertEquals(0L, result.memory.totalPssBytes)
        assertEquals(1024L * 1024, result.memory.rssBytes)
        assertEquals(123456789L * 1024, result.memory.vssBytes)
        assertEquals(0L, result.memory.swapBytes)
        assertNull(result.process.foreground)
        assertNull(result.process.startedAt)
        assertEquals(55L, result.process.sdkInitializedAt)
    }

    /** 数值超出安全范围不伪造零；proc 不可读时保留可用的其他内容。 */
    @Test
    fun handlesOverflowAndUnreadableProc() {
        val source = FakeSource().apply {
            debug = DebugMemorySnapshot(Long.MAX_VALUE, 1, 1, 0)
            unreadable = "/proc/meminfo"
        }
        val memory = collector(source).collect(100L, false).memory
        assertNull(memory.javaHeapPssBytes)
        assertEquals("partial", memory.status)
        assertNull(memory.meminfo)
        assertEquals(0L, memory.javaHeapUsedBytes)
        assertEquals(1024L * 1024, memory.rssBytes)
    }

    /** 文件描述符消失时只保留编号，私有目标不会暴露文件名。 */
    @Test
    fun handlesFdRaceAndRedactsTargets() {
        val source = FakeSource().apply { fds = listOf(3, 4, 5); vanished = 4 }
        val files = collector(source).collect(100L, false).openFiles
        assertEquals("partial", files.status)
        assertEquals("io_error", files.reason)
        assertEquals(3, files.count)
        assertEquals("[path]", files.entries[0].target)
        assertNull(files.entries[1].target)
        assertEquals("[socket]", files.entries[2].target)
        assertFalse(files.toString().contains("secret-file"))
    }

    /** FD 计数和保存条目数量分开表达，OOM 时跳过高分配明细。 */
    @Test
    fun capsFdDetailsAndSkipsExpensiveCollectionForOom() {
        val source = FakeSource().apply { fds = (0 until 200).toList() }
        val collector = collector(source)
        val files = collector.collect(100L, false).openFiles
        assertEquals(200, files.count)
        assertEquals(128, files.entries.size)
        assertTrue(files.truncated)
        val oom = collector.collect(100L, true)
        assertEquals("skipped", oom.openFiles.status)
        assertEquals("memory_pressure", oom.openFiles.reason)
        assertEquals(0L, oom.memory.javaHeapUsedBytes)
        assertEquals("partial", oom.memory.status)
    }

    /** 未观察到 Activity 启动时不能把状态猜成后台。 */
    @Test
    fun lifecycleStateStartsUnknown() {
        val state = CrashForegroundState()
        assertNull(state.current())
        state.stopped()
        assertNull(state.current())
        state.started()
        assertEquals(true, state.current())
        state.started()
        state.stopped()
        assertEquals(true, state.current())
        state.stopped()
        assertEquals(false, state.current())
    }

    /** Native 回调读取原进程堆并保留零值，proc/FD 不由回调重复读取。 */
    @Test
    fun capturesNativeJavaHeapWithoutReadingProcOrFiles() {
        val source = FakeSource().apply { unreadable = "/proc/meminfo"; vanished = 3 }
        val result = collector(source).collectNativeJava(100)
        assertEquals("ok", result.memory.status)
        assertEquals(0L, result.memory.javaHeapUsedBytes)
        assertEquals(7L * 1024, result.memory.javaHeapPssBytes)
        assertEquals(0L, result.memory.nativeHeapAllocatedBytes)
        assertEquals(42, result.process.pid)
        assertEquals(29, result.device.apiLevel)
        assertNull(result.memory.meminfo)
    }

    /** 为纯 JVM 测试注入静态设备与时间。 */
    private fun collector(source: FakeSource): EnvironmentDiagnosticsCollector = EnvironmentDiagnosticsCollector(
        source = source,
        environment = CachedCrashEnvironment(pid = 42, processName = "com.example", sdkInitializedAt = 55,
            processStartedAt = null, apiLevel = 29, abis = listOf("arm64-v8a"), manufacturer = "test",
            brand = "test", buildFingerprint = "test"),
        foreground = { null }, clock = { 99L }, nanoTime = { 0L },
    )

    /** 提供可控内存和 proc 读取结果。 */
    private class FakeSource : CrashEnvironmentSource {
        /** 可注入 Debug 数据。 */ var debug = DebugMemorySnapshot(7, 9, 0, 0)
        /** 不可读的 proc 文件。 */ var unreadable: String? = null
        /** 进程 FD 列表。 */ var fds = listOf(3)
        /** 读取时消失的 FD。 */ var vanished: Int? = null
        /** 返回已使用为零的 Runtime 堆。 */
        override fun runtimeHeap() = RuntimeHeapSnapshot(0, 1000)
        /** 返回可控 Debug 数据。 */
        override fun debugMemory() = debug
        /** 返回合成 proc 内容。 */
        override fun readProc(path: String, maxBytes: Int): BoundedProcText {
            if (path == unreadable) throw IOException("test")
            return BoundedProcText(if (path.endsWith("status"))
                "VmRSS:\t1024 kB\nVmSize:\t123456789 kB\nVmSwap:\t0 kB" else "sample", false)
        }
        /** 返回合成 FD 列表。 */
        override fun fileDescriptors() = fds
        /** 用私有文件名和 socket 验证分类脱敏。 */
        override fun fileTarget(fd: Int): String {
            if (fd == vanished) throw IOException("gone")
            return if (fd == 5) "socket:[123]" else "/data/user/0/com.example/files/secret-file"
        }
    }
}
