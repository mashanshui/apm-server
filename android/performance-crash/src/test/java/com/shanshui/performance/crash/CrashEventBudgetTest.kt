package com.shanshui.performance.crash

import com.google.gson.GsonBuilder
import com.shanshui.performance.network.CrashBatchRequest
import com.shanshui.performance.network.CrashDiagnostics
import com.shanshui.performance.network.CrashDevice
import com.shanshui.performance.network.CrashEvent
import com.shanshui.performance.network.CrashLogcat
import com.shanshui.performance.network.CrashPayload
import com.shanshui.performance.network.CrashProcess
import com.shanshui.performance.network.CrashedThread
import com.shanshui.performance.network.LogcatBuffer
import com.shanshui.performance.network.OpenFile
import com.shanshui.performance.network.OpenFiles
import com.shanshui.performance.network.OtherThread
import com.shanshui.performance.network.OtherThreads
import com.shanshui.performance.network.StackFrame
import com.shanshui.performance.network.ThrowableNode
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证中文、JSON 转义和最小批次预算下不会丢失核心异常。 */
class CrashEventBudgetTest {
    /** 使用生产上传器相同的 JSON 编码。 */
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    /** 预留的 UUID 长度与生产请求相同。 */
    private val requestId = "00000000-0000-4000-8000-000000000000"

    /** 超长主异常先裁剪消息再裁剪帧，仍能装入最小配置的批次。 */
    @Test
    fun keepsMinimumCoreUnderSmallestBatchLimit() {
        val frame = StackFrame("测试类".repeat(80), "方法\"\\".repeat(70), "文件.kt", 10, true)
        val original = event(CrashPayload(throwableChain = listOf(
            ThrowableNode("java.lang.IllegalStateException", "中文\"\\".repeat(800), List(200) { frame }),
        )))
        val budget = CrashEventBudget(16 * 1024)
        val core = budget.prepareCore(original)
        val frozen = budget.freeze(core, CrashDiagnostics())
        val batchBytes = gson.toJson(CrashBatchRequest(requestId, listOf(frozen))).toByteArray(StandardCharsets.UTF_8).size
        assertTrue(batchBytes <= 16 * 1024)
        assertTrue(requireNotNull(frozen.crash).throwableChain.isNotEmpty())
        assertTrue(frozen.crash!!.throwableChain.first().frames.isNotEmpty())
        assertTrue(frozen.crash!!.diagnostics!!.coreTruncation!!.truncated)
    }

    /** 辅助内容按预算裁剪时保留主线程、最近日志和明确的截断状态。 */
    @Test
    fun limitsDiagnosticsAndPreservesMainThreadAndNewestLogs() {
        val budget = CrashEventBudget(16 * 1024)
        val core = budget.prepareCore(event(CrashPayload(throwableChain = listOf(
            ThrowableNode("java.lang.IllegalStateException", frames = listOf(StackFrame("A", "run"))),
        ))))
        val threadFrame = StackFrame("A", "run", "A.kt", 1, true)
        val threads = List(70) { index -> OtherThread(
            javaId = (index + 1).toString(), name = "线程$index", state = "WAITING", priority = 5,
            daemon = false, mainThread = index == 69, frames = List(35) { threadFrame },
        ) }
        val snapshot = CrashDiagnostics(
            otherThreads = OtherThreads(status = "ok", observedCount = 70, omittedCount = 0, threads = threads),
            logcat = CrashLogcat(status = "ok", main = LogcatBuffer(status = "ok",
                lines = List(210) { index -> "旧日志$index" + "中\"\\".repeat(1000) } + "最后一行")),
            openFiles = OpenFiles(status = "ok", count = 150,
                entries = List(150) { index -> OpenFile(index, "[path]") }),
        )
        val frozen = budget.freeze(core, snapshot)
        val diagnostics = requireNotNull(frozen.crash?.diagnostics)
        val batchBytes = gson.toJson(CrashBatchRequest(requestId, listOf(frozen))).toByteArray(StandardCharsets.UTF_8).size
        assertTrue(batchBytes <= 16 * 1024)
        assertTrue(requireNotNull(diagnostics.otherThreads).threads.any { it.mainThread })
        assertTrue(diagnostics.otherThreads!!.threads.size <= 64)
        assertTrue(diagnostics.otherThreads!!.threads.all { it.frames.size <= 32 })
        assertTrue(diagnostics.otherThreads!!.truncated)
        assertTrue(requireNotNull(diagnostics.logcat).truncated)
        assertEquals("最后一行", diagnostics.logcat!!.main!!.lines.lastOrNull())
        assertFalse(diagnostics.logcat!!.main!!.lines.any { budget.encodedBytes(it) > 2048 })
        assertTrue(requireNotNull(diagnostics.openFiles).entries.size <= 128)
        assertNotNull(diagnostics.otherThreads!!.omittedCount)
    }

    /** 冻结时设备或事件超限，已写入核心文件的线程与进程身份保持一致。 */
    @Test
    fun preservesStagedIdentityWhenMetadataAndBatchAreFull() {
        val budget = CrashEventBudget(16 * 1024)
        val crashed = CrashedThread(status = "ok", javaId = "123", name = "线程".repeat(300),
            state = "RUNNABLE", priority = 5, daemon = false, mainThread = false, systemTid = 456)
        val process = CrashProcess(status = "ok", pid = 789, name = "process".repeat(150), foreground = true)
        val original = event(CrashPayload(throwableChain = listOf(
            ThrowableNode("java.lang.IllegalStateException", "异常".repeat(5000), listOf(StackFrame("A", "run"))),
        ), diagnostics = CrashDiagnostics(crashedThread = crashed, process = process)))
        val core = budget.prepareCore(original)
        val staged = requireNotNull(core.crash?.diagnostics)
        val frozen = budget.freeze(core, staged.copy(device = CrashDevice(status = "ok",
            buildFingerprint = "设备".repeat(3000), abis = listOf("arm64-v8a"))))
        val result = requireNotNull(frozen.crash?.diagnostics)
        assertEquals(staged.crashedThread, result.crashedThread)
        assertEquals(staged.process, result.process)
        assertTrue(gson.toJson(CrashBatchRequest(requestId, listOf(frozen))).toByteArray(StandardCharsets.UTF_8).size <= 16 * 1024)
    }

    /** 构造固定的安全测试事件。 */
    private fun event(payload: CrashPayload): CrashEvent = CrashEvent(
        schemaVersion = 2, eventId = "event-budget", eventType = "crash", occurredAt = 1,
        sessionId = "session", processId = "11111111-1111-4111-8111-111111111111",
        anonymousDeviceId = "device", packageName = "com.example.app", appVersion = "1",
        versionCode = 1, buildId = "build", environment = "test", channel = "test",
        osVersion = "35", deviceModel = "test", crash = payload,
    )
}
