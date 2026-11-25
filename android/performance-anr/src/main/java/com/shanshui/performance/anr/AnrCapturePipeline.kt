package com.shanshui.performance.anr

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.shanshui.performance.anr.internal.SignalAnrTracer
import com.shanshui.performance.diagnostics.AndroidCrashEnvironmentSource
import com.shanshui.performance.diagnostics.DiagnosticSanitizer
import com.shanshui.performance.diagnostics.EnvironmentDiagnosticsCollector
import com.shanshui.performance.diagnostics.LogcatDiagnosticsCollector
import com.shanshui.performance.network.CrashDevice
import com.shanshui.performance.network.CrashLogcat
import com.shanshui.performance.network.CrashMemory
import com.shanshui.performance.network.CrashProcess
import com.shanshui.performance.network.OpenFiles
import com.shanshui.performance.network.TransportSession
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 将已判定 ANR 的核心、异步分区和冻结上传连接起来。 */
internal class AnrCapturePipeline(
    /** 宿主应用用于读取本进程状态。 */ private val application: Application,
    /** 原进程的报告入口。 */ private val reports: AnrReports,
    /** 采集记录和冻结队列。 */ private val store: AnrCaptureStore,
    /** 可选的共享传输会话，由调用方持有。 */ private val session: TransportSession?,
    /** 是否显式启用上传。 */ uploadEnabled: Boolean,
    /** 周期刷新间隔。 */ uploadIntervalMillis: Long,
) : Closeable {
    /** 仅在启用且存在可识别会话时绑定目标。 */
    private val target = if (uploadEnabled && session != null) anrTargetFingerprint(session) else null
    /** 原进程可缓存的环境字段。 */
    private val cachedEnvironment = AndroidCrashEnvironmentSource.cache(System.currentTimeMillis())
    /** 采集线程与有界等待队列，避免重复信号无限创建线程。 */
    private val workers = ThreadPoolExecutor(3, 3, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(48)) { runnable ->
        Thread(runnable, "Performance-ANR-Evidence").apply { isDaemon = true }
    }
    /** 冻结定时器不执行可能阻塞的网络请求。 */
    private val timer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "Performance-ANR-Timer").apply { isDaemon = true }
    }
    /** 网络工作线程与采集截止时间分离。 */
    private val network = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Performance-ANR-Upload").apply { isDaemon = true }
    }
    /** 当前正在使用的日志采集器，关闭时请求取消。 */
    private val logCollectors = java.util.Collections.synchronizedSet(mutableSetOf<LogcatDiagnosticsCollector>())
    /** 避免关闭后的异步任务继续提交。 */
    private val closed = AtomicBoolean(false)
    /** 已冻结 trace 的字节预算处理。 */
    private val factory = AnrEventFactory()
    /** 上传器只借用传输会话。 */
    private val uploader = if (target != null && session != null) AnrUploader(store, target,
        AnrHttpSender(session.retrofit.create(AnrIngestApi::class.java)), log = ::log) else null

    /** 恢复上次进程已提交的材料，再按配置启动有界刷新。 */
    init {
        store.recover()
        if (reports.reconcileEvidence() is AnrReportResult.Failure) {
            log("ANR 本地诊断副本清理失败")
        }
        if (uploadEnabled && target == null) log("ANR 未配置可识别上传目标，继续本地采集")
        if (uploader != null) {
            timer.scheduleWithFixedDelay({ flushAsync() }, 0, uploadIntervalMillis, TimeUnit.MILLISECONDS)
        }
    }

    /** 先持久化原身份和核心，再并发启动系统说明与通用诊断。 */
    fun begin(report: AnrReport) {
        if (closed.get()) return
        /** 系统状态分支已取得的说明随核心一起提交，并再次确认归属。 */
        val initial = factory.initialInfo(report, System.currentTimeMillis())
        val info = if (initial != null && !SignalAnrTracer.isCaptureUnambiguous(report.id)) {
            AnrTextPartition("unavailable", "ambiguous", capturedAt = initial.capturedAt)
        } else initial
        val record = AnrCaptureRecord(report.copy(trace = null, traceBase64 = null), target,
            DiagnosticSanitizer.sanitize(Build.VERSION.RELEASE.orEmpty().ifBlank { "unknown" }, 64),
            DiagnosticSanitizer.sanitize(Build.MODEL.orEmpty().ifBlank { "unknown" }, 256), info = info)
        if (!store.begin(record)) return
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CAPTURE_MILLIS)
        try {
            timer.schedule({ freeze(report.id) }, CAPTURE_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: RuntimeException) {
            store.freeze(report.id, "interrupted")
            return
        }
        submit { collectEnvironment(report, deadline) }
        submit { collectLogcat(report.id, deadline) }
        submit { collectInfo(report, deadline) }
        log("ANR 现场采集已启动")
    }

    /** 冻结提交暂时失败时按有限间隔重试，不再接受迟到分区。 */
    private fun freeze(id: String) {
        if (closed.get()) return
        if (store.freeze(id)) {
            flushAsync()
        } else {
            try { timer.schedule({ freeze(id) }, FREEZE_RETRY_MILLIS, TimeUnit.MILLISECONDS) }
            catch (_: RuntimeException) { log("ANR 冻结重试调度失败") }
        }
    }

    /** 已完成 trace 根据采集标识与报告和原进程身份核对。 */
    fun traceAvailable(captureId: String) {
        if (closed.get()) return
        val report = (reports.read(captureId) as? AnrReportResult.Success)?.value ?: return
        val trace = report.trace ?: return
        val part = factory.boundedTrace(trace, report.traceTruncated == true, System.currentTimeMillis())
        store.update(captureId) { current -> current.copy(trace = part) }
    }

    /** 将内存、文件、进程和设备分别持久化，避免中断时丢失已完成分区。 */
    private fun collectEnvironment(report: AnrReport, deadline: Long) {
        val collector = EnvironmentDiagnosticsCollector(AndroidCrashEnvironmentSource(), cachedEnvironment,
            foreground = { report.foreground })
        try {
            collector.collect(deadline, false,
                onMemory = { value -> store.update(report.id) { it.copy(diagnostics = it.diagnostics.copy(memory = value)) } },
                onOpenFiles = { value -> store.update(report.id) { it.copy(diagnostics = it.diagnostics.copy(openFiles = value)) } },
                onProcess = { value -> store.update(report.id) { it.copy(diagnostics = it.diagnostics.copy(process = value)) } },
                onDevice = { value -> store.update(report.id) { it.copy(diagnostics = it.diagnostics.copy(device = value)) } },
            )
            log("ANR 环境分区采集完成")
        } catch (failure: Throwable) {
            val reason = reason(failure)
            store.update(report.id) { current -> current.copy(diagnostics = current.diagnostics.copy(
                memory = current.diagnostics.memory ?: CrashMemory(status = "unavailable", reason = reason),
                openFiles = current.diagnostics.openFiles ?: OpenFiles(status = "unavailable", reason = reason),
                process = current.diagnostics.process ?: CrashProcess(status = "unavailable", reason = reason),
                device = current.diagnostics.device ?: CrashDevice(status = "unavailable", reason = reason))) }
            log("ANR 环境分区采集失败：$reason")
        }
    }

    /** Logcat 使用独立的进程与超时资源。 */
    private fun collectLogcat(id: String, deadline: Long) {
        val collector = LogcatDiagnosticsCollector()
        logCollectors.add(collector)
        try {
            val logs = collector.collect(android.os.Process.myPid(), Build.VERSION.SDK_INT, deadline, false)
            store.update(id) { it.copy(diagnostics = it.diagnostics.copy(logcat = logs)) }
            log("ANR 日志采集完成：${logs.status}")
        } catch (failure: Throwable) {
            val reason = reason(failure)
            store.update(id) { it.copy(diagnostics = it.diagnostics.copy(
                logcat = CrashLogcat(status = "unavailable", reason = reason))) }
            log("ANR 日志采集失败：$reason")
        } finally {
            logCollectors.remove(collector)
            collector.close()
        }
    }

    /** 消息超时分支在后台补查；直接系统判定沿用已匹配的 longMsg。 */
    private fun collectInfo(report: AnrReport, deadline: Long) {
        val lookup = AnrInfoLookup(read = {
            val manager = application.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: throw IllegalStateException("activity manager unavailable")
            manager.processesInErrorState.orEmpty().map { state ->
                AnrErrorState(state.uid, state.pid, state.condition == ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING,
                    state.longMsg)
            }
        }, uid = android.os.Process.myUid(), pid = android.os.Process.myPid(),
            unambiguous = SignalAnrTracer::isCaptureUnambiguous)
        val info = lookup.collect(report.id, report.anrInfo, report.fromProcessErrorState != true, deadline)
        val actual = if (info.text != null && report.anrInfoTruncated == true)
            info.copy(status = "partial", reason = "size_limit", truncated = true) else info
        store.update(report.id) { it.copy(info = actual) }
        log("ANR 系统说明采集完成：${actual.status}")
    }

    /** 将任务放入有界线程池，关闭或饱和时交由冻结逻辑标记未完成。 */
    private fun submit(action: () -> Unit) {
        try { workers.execute(action) } catch (_: RuntimeException) { log("ANR 采集工作线程不可用") }
    }

    /** 显式或定时刷新只提交网络工作，不阻塞调用线程。 */
    fun flushAsync() {
        if (closed.get() || uploader == null) return
        try {
            network.execute {
                try { uploader.flush() } catch (failure: Exception) { log("ANR 上传失败：${failure.javaClass.simpleName}") }
            }
        } catch (_: RuntimeException) { log("ANR 上传线程不可用") }
    }

    /** 终止当前采集并冻结已提交现场；不关闭借用的网络会话。 */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        timer.shutdownNow()
        workers.shutdownNow()
        synchronized(logCollectors) { logCollectors.forEach { it.cancel() } }
        store.recover()
        network.shutdownNow()
        log("ANR 采集与上传工作线程已关闭")
    }

    /** 异常只映射为有限的安全原因。 */
    private fun reason(failure: Throwable): String = when (failure) {
        is OutOfMemoryError -> "memory_pressure"
        is SecurityException -> "permission_denied"
        is InterruptedException -> "interrupted"
        else -> "io_error"
    }

    /** 只输出阶段、状态和错误类别。 */
    private fun log(message: String) {
        if (message.contains("失败") || message.contains("不可用") || message.contains("已满"))
            Log.w("PerformanceAnr", message) else Log.i("PerformanceAnr", message)
    }

    /** 补充现场窗口。 */
    private companion object {
        const val CAPTURE_MILLIS = 5_000L
        const val FREEZE_RETRY_MILLIS = 30_000L
    }
}
