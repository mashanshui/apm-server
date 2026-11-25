package com.shanshui.performance.anr

import com.shanshui.performance.diagnostics.BoundedProcText
import com.shanshui.performance.diagnostics.CachedCrashEnvironment
import com.shanshui.performance.diagnostics.CrashEnvironmentSource
import com.shanshui.performance.diagnostics.DebugMemorySnapshot
import com.shanshui.performance.diagnostics.EnvironmentDiagnosticsCollector
import com.shanshui.performance.diagnostics.RuntimeHeapSnapshot
import com.shanshui.performance.network.CrashMemory
import com.shanshui.performance.network.OpenFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ANR 复用 Crash 环境采集规则并逐分区提交。 */
class AnrEnvironmentCaptureTest {
    /** 受限进程文件和 FD 不影响已获得的零值及其他分区。 */
    @Test
    fun preservesZeroWhenProcAndDescriptorsAreDenied() {
        val commits = mutableListOf<String>()
        var memory: CrashMemory? = null
        var files: OpenFiles? = null
        val collector = EnvironmentDiagnosticsCollector(DeniedSource(), cached(), { true }, clock = { 10 }, nanoTime = { 0 })
        val result = collector.collect(100, false,
            onMemory = { memory = it; commits += "memory" },
            onOpenFiles = { files = it; commits += "files" },
            onProcess = { commits += "process" }, onDevice = { commits += "device" })
        assertEquals(listOf("memory", "files", "process", "device"), commits)
        assertEquals(0L, memory?.javaHeapUsedBytes)
        assertEquals("partial", memory?.status)
        assertEquals("permission_denied", memory?.reason)
        assertEquals("permission_denied", files?.reason)
        assertEquals(22, result.process.pid)
        assertEquals(35, result.device.apiLevel)
    }

    /** 截止时间已到时标记超时，不伪造成功的文件采集。 */
    @Test
    fun recordsDeadlineWithoutDroppingCheapZeroValue() {
        val collector = EnvironmentDiagnosticsCollector(DeniedSource(), cached(), { false }, clock = { 10 }, nanoTime = { 100 })
        val result = collector.collect(100, false)
        assertEquals("timeout", result.memory.reason)
        assertEquals(0L, result.memory.javaHeapUsedBytes)
        assertEquals("skipped", result.openFiles.status)
        assertEquals("timeout", result.openFiles.reason)
        assertTrue(result.openFiles.entries.isEmpty())
    }

    /** 所有 proc 和 FD 读取均模拟权限失败。 */
    private class DeniedSource : CrashEnvironmentSource {
        /** 返回有效的零堆值。 */
        override fun runtimeHeap() = RuntimeHeapSnapshot(0, 0)
        /** 返回有效的零 PSS 值。 */
        override fun debugMemory() = DebugMemorySnapshot(0, 0, 0, 0)
        /** 模拟 proc 文本权限失败。 */
        override fun readProc(path: String, maxBytes: Int): BoundedProcText = throw SecurityException()
        /** 模拟 FD 列举权限失败。 */
        override fun fileDescriptors(): List<Int> = throw SecurityException()
        /** 模拟 FD 链接读取权限失败。 */
        override fun fileTarget(fd: Int): String = throw SecurityException()
    }

    /** 固定的公开进程与设备信息。 */
    private fun cached() = CachedCrashEnvironment(22, "com.example", 5, null, 35,
        listOf("arm64-v8a"), "demo", "demo", "test-build")
}
