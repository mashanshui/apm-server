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
import com.shanshui.performance.network.NativeBacktrace
import com.shanshui.performance.network.NativeCrash
import com.shanshui.performance.network.NativeFrame
import com.shanshui.performance.network.NativeModule
import com.shanshui.performance.network.NativeRegisters
import com.shanshui.performance.network.NativeSignal
import com.shanshui.performance.network.NativeThread
import com.shanshui.performance.network.NativeThreads
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
        assertTrue(requireNotNull(frozen.crash?.throwableChain).isNotEmpty())
        assertTrue(requireNotNull(frozen.crash?.throwableChain).first().frames.isNotEmpty())
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

    /** 多字节与转义文本下，Native 核心裁剪后仍保留原始身份并符合小批次。 */
    @Test
    fun preservesNativeSignalRegistersAndModuleReferencesUnderSmallBatch() {
        val budget = CrashEventBudget(16 * 1024)
        val modules = List(140) { index -> NativeModule("m$index", "lib$index.so", abi = "arm64-v8a") }
        val native = NativeCrash(abi = "arm64-v8a", systemTid = 42,
            signal = NativeSignal(11, "SIGSEGV", 1, faultAddress = "0x0", abortMessage = "中文\"\\".repeat(1000)),
            registers = NativeRegisters(status = "ok", values = mapOf("pc" to "0xffffffffffffffff", "sp" to "0x0")),
            modules = modules, backtrace = NativeBacktrace(status = "ok", frames = List(140) { index ->
                NativeFrame(index, "m$index", "0x11", functionName = "方法\"\\".repeat(100))
            }))
        val core = budget.prepareCore(event(CrashPayload(kind = "native", nativeCrash = native)))
        val frozen = budget.freeze(core, CrashDiagnostics())
        val saved = requireNotNull(frozen.crash?.nativeCrash)
        val knownModules = saved.modules.map { it.id }.toSet()
        assertEquals("0x0", saved.signal.faultAddress)
        assertEquals("0xffffffffffffffff", saved.registers.values["pc"])
        assertEquals(42, saved.systemTid)
        assertTrue(saved.backtrace.frames.size <= 128)
        assertTrue(saved.modules.size <= 128)
        assertTrue(saved.backtrace.frames.all { it.moduleId == null || it.moduleId in knownModules })
        assertTrue(requireNotNull(frozen.crash?.diagnostics?.coreTruncation).truncated)
        assertTrue(gson.toJson(CrashBatchRequest(requestId, listOf(frozen))).toByteArray(StandardCharsets.UTF_8).size <= 16 * 1024)
    }

    /** 两类线程共用数量和字节限额，不能因同名或 ID 不同误合并。 */
    @Test
    fun sharesNativeAndJavaThreadBudgetAndKeepsMainThreads() {
        val budget = CrashEventBudget(240 * 1024)
        val native = NativeCrash(abi = "arm64-v8a", systemTid = 42, signal = NativeSignal(11, "SIGSEGV", 1),
            registers = NativeRegisters(status = "ok", values = mapOf("pc" to "0x1")),
            modules = listOf(NativeModule("m0", "liba.so", abi = "arm64-v8a")),
            backtrace = NativeBacktrace(status = "ok", frames = listOf(NativeFrame(0, "m0", "0x1"))))
        val core = budget.prepareCore(event(CrashPayload(kind = "native", nativeCrash = native)))
        val java = List(50) { index -> OtherThread(javaId = (index + 1).toString(), name = "same", state = "WAITING",
            priority = 5, daemon = false, mainThread = index == 49, relationToCrash = "unknown",
            frames = List(40) { StackFrame("类".repeat(80), "方法\"\\".repeat(20)) }) }
        val otherNative = List(50) { index -> NativeThread(systemTid = index + 100, name = "same", mainThread = index == 49,
            backtrace = NativeBacktrace(status = "ok", frames = List(40) { frame ->
                NativeFrame(frame, if (frame == 0) "missing" else "m0", "0x11", functionName = "函数".repeat(80))
            })) }
        val snapshot = CrashDiagnostics(otherThreads = OtherThreads(status = "ok", observedCount = 50, omittedCount = 0, threads = java),
            nativeThreads = NativeThreads(status = "ok", observedCount = 50, omittedCount = 0, threads = otherNative))
        val diagnostics = requireNotNull(budget.freeze(core, snapshot).crash?.diagnostics)
        val javaResult = requireNotNull(diagnostics.otherThreads)
        val nativeResult = requireNotNull(diagnostics.nativeThreads)
        assertTrue(javaResult.threads.size + nativeResult.threads.size <= 64)
        assertTrue(budget.encodedBytes(javaResult) + budget.encodedBytes(nativeResult) <= 48 * 1024)
        assertTrue(javaResult.threads.any { it.mainThread })
        assertTrue(nativeResult.threads.any { it.mainThread == true })
        assertTrue(javaResult.threads.all { it.frames.size <= 32 })
        assertTrue(nativeResult.threads.all { it.backtrace.frames.size <= 32 })
        assertTrue(nativeResult.threads.flatMap { it.backtrace.frames }.all { it.moduleId == null || it.moduleId == "m0" })
        assertTrue(javaResult.truncated && nativeResult.truncated)
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
