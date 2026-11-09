package com.shanshui.performance.crash

import com.shanshui.performance.network.CrashDevice
import com.shanshui.performance.network.CrashDiagnostics
import com.shanshui.performance.network.CrashEvent
import com.shanshui.performance.network.CrashLogcat
import com.shanshui.performance.network.CrashMemory
import com.shanshui.performance.network.CrashProcess
import com.shanshui.performance.network.CrashedThread
import com.shanshui.performance.network.OpenFiles
import com.shanshui.performance.network.OtherThreads
import java.util.concurrent.ExecutorService
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** 先固定核心记录，再等待有界辅助采集并冻结一次最终事件。 */
internal class CrashCaptureCoordinator(
    /** 现有文件队列。 */ private val queue: FileCrashQueue,
    /** 单事件与批次预算。 */ private val budget: CrashEventBudget,
    /** 只产生快照的采集器。 */ private val collector: CrashDiagnosticsCapture,
    /** 初始化时预建的单工作线程。 */ private val worker: ExecutorService,
    /** 只接收固定阶段与计数的安全日志。 */ private val logger: (String) -> Unit = {},
    /** 处理器等待辅助结果的总预算。 */ private val waitMillis: Long = 1500,
    /** 单调时间来源。 */ private val nanoTime: () -> Long = { System.nanoTime() },
) {
    /** 保存核心、等待采集并冻结；工作者永远不能修改队列。 */
    fun capture(event: CrashEvent, thread: Thread, tid: Int?, throwable: Throwable): Boolean {
        // 协调器入口单调时间，包含核心保存和冻结，不包含后续上传。
        val captureStartedNanos = nanoTime()
        // 进程级粗略堆使用量，不能作为单次分配或内存峰值。
        val heapBefore = heapUsedBytes()
        // 只读取便宜的线程与缓存进程身份，随核心原子持久化以便中断恢复。
        val crashed = try { collector.captureCrashed(thread, tid) }
        catch (_: Throwable) { CrashedThread(status = "unavailable", reason = "io_error") }
        val process = try { collector.captureProcess() }
        catch (_: Throwable) { CrashProcess(status = "unavailable", reason = "io_error") }
        val payload = requireNotNull(event.crash)
        val core = budget.prepareCore(event.copy(crash = payload.copy(diagnostics = CrashDiagnostics(
            coreTruncation = payload.diagnostics?.coreTruncation, crashedThread = crashed, process = process,
        ))))
        if (!queue.beginCapture(core)) {
            logger("crash core capture skipped: duplicate=true")
            return false
        }
        logger("crash core captured: bytes=${budget.encodedBytes(core)}")
        val deadline = nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis)
        val skipExpensive = containsOom(throwable)
        var failure: String? = null
        val future = try {
            worker.submit<CrashDiagnostics> { collector.collect(thread, tid, deadline, skipExpensive) }
        } catch (_: Throwable) {
            failure = "io_error"
            null
        }
        // 将工作者等待与核心落盘、冻结的整体耗时分开观察。
        val waitStartedNanos = nanoTime()
        val snapshot = if (future == null) null else try {
            future.get(waitMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            failure = "timeout"
            runCatching { collector.cancel() }
            runCatching { future.cancel(true) }
            null
        } catch (_: InterruptedException) {
            failure = "interrupted"
            runCatching { collector.cancel() }
            runCatching { future.cancel(true) }
            Thread.currentThread().interrupt()
            null
        } catch (error: Throwable) {
            failure = if (skipExpensive || (error is ExecutionException && error.cause is OutOfMemoryError))
                "memory_pressure" else "io_error"
            runCatching { collector.cancel() }
            null
        }
        // 当前处理线程实际等待的毫秒数。
        val waitMillisObserved = TimeUnit.NANOSECONDS.toMillis((nanoTime() - waitStartedNanos).coerceAtLeast(0))
        val diagnostics = (snapshot ?: skipped(requireNotNull(failure))).copy(
            crashedThread = core.crash?.diagnostics?.crashedThread,
            process = core.crash?.diagnostics?.process,
        )
        val frozen = budget.freeze(core, diagnostics)
        val saved = queue.freezeCapture(frozen)
        // 包含核心保存与最终冻结的粗略总耗时。
        val captureMillisObserved = TimeUnit.NANOSECONDS.toMillis((nanoTime() - captureStartedNanos).coerceAtLeast(0))
        // 冻结后的进程级粗略堆使用量，可能受其他线程和 GC 影响。
        val heapAfter = heapUsedBytes()
        // 空值表示无法读取，负数表示期间进程堆使用量下降。
        val heapDelta = if (heapBefore != null && heapAfter != null) (heapAfter - heapBefore).toString() else "unknown"
        logger("crash capture frozen: saved=$saved bytes=${budget.encodedBytes(frozen)} reason=${failure ?: "none"} " +
            "wait_ms=$waitMillisObserved capture_ms=$captureMillisObserved heap_delta_bytes=$heapDelta")
        return saved
    }

    /** 尽力读取进程 Java 堆当前使用量，失败不影响核心事件。 */
    private fun heapUsedBytes(): Long? = try {
        // Runtime 提供当前进程的 Java 堆容量及空闲量。
        val runtime = Runtime.getRuntime()
        (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0)
    } catch (_: Throwable) {
        null
    }

    /** 有界检查异常原因链，避免循环原因链和昂贵现场采集。 */
    private fun containsOom(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        repeat(16) {
            if (current is OutOfMemoryError) return true
            current = runCatching { current?.cause }.getOrNull() ?: return false
        }
        return false
    }

    /** 未完成的分区标记为跳过，不补造下一进程或迟到现场。 */
    private fun skipped(reason: String): CrashDiagnostics = CrashDiagnostics(
        otherThreads = OtherThreads(status = "skipped", reason = reason),
        logcat = CrashLogcat(status = "skipped", reason = reason),
        memory = CrashMemory(status = "skipped", reason = reason),
        openFiles = OpenFiles(status = "skipped", reason = reason),
        process = CrashProcess(status = "skipped", reason = reason),
        device = CrashDevice(status = "skipped", reason = reason),
    )
}
