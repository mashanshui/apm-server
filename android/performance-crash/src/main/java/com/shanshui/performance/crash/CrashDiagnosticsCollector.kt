package com.shanshui.performance.crash

import com.shanshui.performance.network.CrashDevice
import com.shanshui.performance.network.CrashDiagnostics
import com.shanshui.performance.network.CrashLogcat
import com.shanshui.performance.network.CrashMemory
import com.shanshui.performance.network.CrashProcess
import com.shanshui.performance.network.CrashedThread
import com.shanshui.performance.network.OpenFiles
import com.shanshui.performance.network.OtherThreads

/** 崩溃现场采集边界，允许处理器在超时后关闭日志读取。 */
internal interface CrashDiagnosticsCapture {
    /** 在处理器入口保存崩溃线程身份。 */
    fun captureCrashed(thread: Thread, tid: Int?): CrashedThread
    /** 在处理器入口冻结当前进程身份。 */
    fun captureProcess(): CrashProcess
    /** 只返回受限快照，不写队列或触发上传。 */
    fun collect(thread: Thread, tid: Int?, deadlineNanos: Long, skipExpensive: Boolean): CrashDiagnostics
    /** 请求终止当前辅助采集，不等待迟到结果。 */
    fun cancel()
}

/** 将独立采集分区组合为一次近似快照，单个分区失败不覆盖其他结果。 */
internal class CrashDiagnosticsCollector(
    /** 线程采集。 */ private val threads: ThreadDiagnosticsCollector,
    /** 内存、进程、FD 和设备采集。 */ private val environment: EnvironmentDiagnosticsCollector,
    /** 固定命令日志采集。 */ private val logcat: LogcatDiagnosticsCollector,
    /** 本进程数值 PID。 */ private val pid: Int?,
    /** 当前 API level。 */ private val apiLevel: Int?,
) : CrashDiagnosticsCapture {
    /** 在处理器线程获取崩溃线程信息。 */
    override fun captureCrashed(thread: Thread, tid: Int?): CrashedThread = threads.captureCrashed(thread, tid)

    /** 在处理器线程取得缓存的进程上下文。 */
    override fun captureProcess(): CrashProcess = environment.captureProcess()

    /** 依次尝试各分区；受控 OOM 之后跳过昂贵采集。 */
    override fun collect(thread: Thread, tid: Int?, deadlineNanos: Long, skipExpensive: Boolean): CrashDiagnostics {
        var memoryPressure = skipExpensive
        val threadResult = try { threads.collect(thread, tid, deadlineNanos, memoryPressure) }
        catch (_: OutOfMemoryError) {
            memoryPressure = true
            ThreadDiagnostics(CrashedThread(status = "unavailable", reason = "memory_pressure"),
                OtherThreads(status = "unavailable", reason = "memory_pressure"))
        } catch (_: Throwable) {
            ThreadDiagnostics(CrashedThread(status = "unavailable", reason = "io_error"),
                OtherThreads(status = "unavailable", reason = "io_error"))
        }
        val environmentResult = try { environment.collect(deadlineNanos, memoryPressure) }
        catch (_: OutOfMemoryError) {
            memoryPressure = true
            unavailableEnvironment("memory_pressure")
        } catch (_: Throwable) {
            unavailableEnvironment("io_error")
        }
        val logs = if (pid == null || apiLevel == null) {
            CrashLogcat(status = "unavailable", reason = "unsupported")
        } else try { logcat.collect(pid, apiLevel, deadlineNanos, memoryPressure) }
        catch (_: OutOfMemoryError) { CrashLogcat(status = "unavailable", reason = "memory_pressure") }
        catch (_: Throwable) { CrashLogcat(status = "unavailable", reason = "io_error") }
        return CrashDiagnostics(crashedThread = threadResult.crashedThread, otherThreads = threadResult.otherThreads,
            logcat = logs, memory = environmentResult.memory, openFiles = environmentResult.openFiles,
            process = environmentResult.process, device = environmentResult.device)
    }

    /** 处理器超时后请求销毁 logcat，迟到结果不会由本对象落盘。 */
    override fun cancel() = logcat.cancel()

    /** 单个环境读取器整体失败时使用安全原因降级。 */
    private fun unavailableEnvironment(reason: String): EnvironmentDiagnostics = EnvironmentDiagnostics(
        memory = CrashMemory(status = "unavailable", reason = reason),
        openFiles = OpenFiles(status = "unavailable", reason = reason),
        process = CrashProcess(status = "unavailable", reason = reason),
        device = CrashDevice(status = "unavailable", reason = reason),
    )
}
