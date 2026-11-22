package com.shanshui.performance.anr

import android.app.Application
import android.util.Log
import android.util.Base64
import com.bytedance.android.bytehook.ByteHook
import com.bytedance.shadowhook.ShadowHook
import com.shanshui.performance.anr.internal.SignalAnrTracer
import com.shanshui.performance.core.PerformanceEventContext
import com.shanshui.performance.core.PerformanceEventContextFactory
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 独立接入和仅本地读取 ANR 结果的入口。 */
object AnrComponent {
    /** 保护进程内 Matrix 单例和重复初始化。 */
    private val lock = Any()
    /** 当前进程复用的 Matrix 采集会话。 */
    private var active: ActiveSession? = null
    /** Matrix 的 hasInit 关闭后不会复位，不能伪称可以再次启动。 */
    private var startedOnce = false

    /** 打开报告读取入口，不加载 ByteHook 或 ANR 原生库。 */
    fun reports(application: Application, config: AnrConfig = AnrConfig()): AnrReports =
        AnrReports(AnrReportStore(root(application), config))

    /** 使用现有公共身份启动 Matrix ANR；关闭或不支持时仍返回可读取报告的句柄。 */
    fun start(
        application: Application,
        config: AnrConfig = AnrConfig(),
        context: PerformanceEventContext = PerformanceEventContextFactory.create(application),
    ): AnrHandle = synchronized(lock) {
        /** 与原生采集状态独立的本地报告入口。 */
        val reports = reports(application, config)
        /** 未启用及不支持设备都不加载任何 ANR 原生代码。 */
        val platformResult = AnrPlatform.check(application, config)
        if (platformResult != null) return@synchronized AnrHandle(platformResult, reports)

        active?.let { current ->
            if (current.config == config && current.context == context) {
                return@synchronized acquire(current, reports)
            }
            return@synchronized AnrHandle(AnrInitResult(AnrInitStatus.FAILED, "configuration_conflict"), reports)
        }
        if (startedOnce) {
            return@synchronized AnrHandle(AnrInitResult(AnrInitStatus.FAILED, "matrix_restart_not_supported"), reports)
        }

        /** ByteHook 使用自动模式及与 Rhea 一致的 ShadowHook 共享模式。 */
        val hookStatus = try {
            ByteHook.init(
                ByteHook.ConfigBuilder()
                    .setMode(ByteHook.Mode.AUTOMATIC)
                    .setShadowhookMode(ShadowHook.Mode.SHARED)
                    .build(),
            )
        } catch (failure: LinkageError) {
            Log.w(TAG, "ByteHook 初始化失败：${failure.javaClass.simpleName}")
            return@synchronized AnrHandle(AnrInitResult(AnrInitStatus.FAILED, "bytehook_load_failed"), reports)
        } catch (failure: RuntimeException) {
            Log.w(TAG, "ByteHook 初始化失败：${failure.javaClass.simpleName}")
            return@synchronized AnrHandle(AnrInitResult(AnrInitStatus.FAILED, "bytehook_init_failed"), reports)
        }
        if (hookStatus != 0) {
            Log.w(TAG, "ByteHook 初始化失败：$hookStatus")
            return@synchronized AnrHandle(AnrInitResult(AnrInitStatus.FAILED, "bytehook_init_failed"), reports)
        }
        if (!AnrNativeLibrary.load()) {
            return@synchronized AnrHandle(AnrInitResult(AnrInitStatus.FAILED, "native_load_failed"), reports)
        }

        /** Matrix 原有两个活动 trace 文件与已完成报告目录隔离。 */
        val activeDir = File(root(application), "active")
        if (!activeDir.isDirectory && !activeDir.mkdirs()) {
            Log.w(TAG, "Matrix trace 目录创建失败")
            return@synchronized AnrHandle(AnrInitResult(AnrInitStatus.FAILED, "trace_directory_failed"), reports)
        }
        /** 持久化线程仅负责 Matrix 已触发回调后的 SDK 结果保存。 */
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "Performance-ANR-Report") }
        /** Matrix 原有信号采集器，仍在调用方线程启动。 */
        val tracer = SignalAnrTracer(
            application,
            File(activeDir, "anr.trace").absolutePath,
            File(activeDir, "manual.trace").absolutePath,
        )
        /** 供宿主更换的报告保存完成回调。 */
        val listener = AtomicReference<AnrReportListener?>(null)
        /** 将 Matrix 实际回调映射为项目报告。 */
        val sink = MatrixReportSink(reports, context, executor, listener)
        tracer.setSignalAnrDetectedListener(sink)
        tracer.setTraceFileListener(sink)
        startedOnce = true
        try {
            tracer.onStartTrace()
        } catch (failure: Throwable) {
            Log.w(TAG, "Matrix ANR 启动失败：${failure.javaClass.simpleName}")
            runCatching { tracer.onCloseTrace() }
            executor.shutdown()
            return@synchronized AnrHandle(AnrInitResult(AnrInitStatus.FAILED, "matrix_start_failed"), reports)
        }

        // Matrix 不返回 sigaction 和 hook 的完整安装结果，不能将其解释为全部来源已验证。
        val result = AnrInitResult(AnrInitStatus.DEGRADED, "matrix_hook_status_unverified")
        val session = ActiveSession(config, context, result, tracer, executor, listener)
        active = session
        Log.i(TAG, "Matrix ANR 启动流程完成，hook 状态待实际信号验证")
        acquire(session, reports)
    }

    /** 复用同一采集器，并为每个 SDK 所有者分别记录关闭责任。 */
    private fun acquire(session: ActiveSession, reports: AnrReports): AnrHandle {
        session.owners++
        return AnrHandle(session.result, reports, session.tracer, session.executor, session.listener) { handle ->
            release(session, handle)
        }
    }

    /** 最后一个所有者关闭时，按 Matrix 原顺序释放采集器。 */
    private fun release(session: ActiveSession, handle: AnrHandle) = synchronized(lock) {
        if (active !== session) return@synchronized
        session.owners--
        if (session.owners > 0) {
            Log.i(TAG, "Matrix ANR 句柄已释放，其他所有者仍在使用")
            return@synchronized
        }
        runCatching { handle.stopCollector() }.onFailure {
            Log.w(TAG, "Matrix ANR 关闭失败：${it.javaClass.simpleName}")
        }
        active = null
        Log.i(TAG, "Matrix ANR 关闭流程完成")
    }

    /** SDK 会话只管理共享所有权，不更改 Matrix 信号处理逻辑。 */
    private class ActiveSession(
        /** 当前采集配置。 */
        val config: AnrConfig,
        /** 当前共享身份。 */
        val context: PerformanceEventContext,
        /** 当前采集器初始化结果。 */
        val result: AnrInitResult,
        /** Matrix 原有采集器。 */
        val tracer: SignalAnrTracer,
        /** 本地保存线程。 */
        val executor: ExecutorService,
        /** Matrix 单回调对应的 SDK 监听器。 */
        val listener: AtomicReference<AnrReportListener?>,
        /** 尚未关闭的 SDK 所有者数量。 */
        var owners: Int = 0,
    )

    /** 返回本功能专用的 noBackup 私有目录。 */
    private fun root(application: Application): File = File(application.noBackupFilesDir, "performance-anr")

    /** 不包含报告正文的日志标签。 */
    private const val TAG = "PerformanceAnr"
}

/** 本地报告操作入口，可在采集关闭或平台不支持时使用。 */
class AnrReports internal constructor(
    /** 与 JNI 无关的本地报告存储。 */
    private val store: AnrReportStore,
) {
    /** 查询每页 1 至 20 条摘要。 */
    fun list(pageSize: Int = 20, afterId: String? = null): AnrReportResult<AnrReportPage> =
        store.list(pageSize, afterId)

    /** 读取指定报告的完整已保存内容。 */
    fun read(id: String): AnrReportResult<AnrReport> = store.read(id)

    /** 删除指定已完成报告。 */
    fun delete(id: String): AnrReportResult<Unit> = store.delete(id)

    /** 按配置的容量和期限清理已完成报告。 */
    fun clean(): AnrReportResult<Int> = store.clean(System.currentTimeMillis())

    /** 供 Matrix 结果适配器提交已捕获内容。 */
    internal fun save(report: AnrReport): AnrReportResult<AnrReportSummary> = store.save(report)
}

/** Matrix ANR 运行句柄；报告在关闭后仍可读取。 */
class AnrHandle internal constructor(
    /** 启动时实际确认的状态。 */
    val initResult: AnrInitResult,
    /** 本地报告读取入口。 */
    val reports: AnrReports,
    /** 当前句柄所引用的 Matrix 采集器。 */
    private val tracer: SignalAnrTracer? = null,
    /** 当前句柄所引用的报告保存线程。 */
    private val executor: ExecutorService? = null,
    /** 可替换的保存完成回调。 */
    private val listener: AtomicReference<AnrReportListener?> = AtomicReference(null),
    /** 关闭当前进程级采集器的操作。 */
    private val closeAction: ((AnrHandle) -> Unit)? = null,
) : Closeable {
    /** 防止重复关闭。 */
    private val closed = AtomicBoolean(false)

    /** Matrix 启动流程已执行且句柄尚未关闭；各 hook 状态仍以 initResult 为准。 */
    val isAvailable: Boolean
        get() = tracer != null && !closed.get()

    /** 替换报告成功保存后的回调。 */
    fun setReportListener(value: AnrReportListener?) {
        listener.set(value)
    }

    /** 按 Matrix 原方式请求一次手动 trace；返回值只表示已发起。 */
    fun printTrace(): Boolean {
        if (!isAvailable) return false
        SignalAnrTracer.printTrace()
        return true
    }

    /** 关闭本句柄拥有的采集器，不删除已经保存的报告。 */
    override fun close() {
        if (closed.compareAndSet(false, true)) closeAction?.invoke(this)
    }

    /** 执行 Matrix 原有关闭并停止接受新的保存任务。 */
    internal fun stopCollector() {
        try {
            tracer?.onCloseTrace()
        } finally {
            executor?.shutdown()
        }
    }
}

/** 将 Matrix 原始回调异步转交本地报告存储。 */
private class MatrixReportSink(
    /** SDK 本地报告入口。 */
    private val reports: AnrReports,
    /** 当前进程公共身份。 */
    private val context: PerformanceEventContext,
    /** 单线程保存执行器。 */
    private val executor: ExecutorService,
    /** 宿主报告完成回调。 */
    private val listener: AtomicReference<AnrReportListener?>,
) : SignalAnrTracer.SignalAnrDetectedListener, SignalAnrTracer.TraceFileListener {
    /** Matrix 外部 SIGQUIT 判定回调。 */
    override fun onAnrDetected(stackTrace: String, message: String, whenMillis: Long, fromError: Boolean, cgroup: String) {
        enqueue(AnrReportType.SIGNAL_ANR) { base ->
            base.copy(javaStack = stackTrace, message = message, messageWhenMillis = whenMillis, fromProcessErrorState = fromError, cgroup = cgroup)
        }
    }

    /** Matrix debugger 分支提供的仍是主线程 Java 栈。 */
    override fun onNativeBacktraceDetected(stackTrace: String, message: String, whenMillis: Long, fromError: Boolean) {
        enqueue(AnrReportType.DEBUGGER_SIGNAL) { base ->
            base.copy(javaStack = stackTrace, message = message, messageWhenMillis = whenMillis, fromProcessErrorState = fromError)
        }
    }

    /** 保存 Matrix 锁环解析回调，不增加新的锁关系推断。 */
    override fun onDeadLockAnrDetected(
        mainThread: String,
        lockThread1: String,
        lockThread2: String,
        waitingList: MutableMap.MutableEntry<IntArray, Array<String>>,
    ) {
        enqueue(AnrReportType.DEADLOCK) { base ->
            base.copy(mainThread = mainThread, lockThread1 = lockThread1, lockThread2 = lockThread2,
                waitingThreadIds = waitingList.key?.toList(), waitingLockTypes = waitingList.value?.toList())
        }
    }

    /** 保存 Matrix nativePollOnce 原始解析回调。 */
    override fun onMainThreadStuckAtNativePollOnce(mainThread: String) {
        enqueue(AnrReportType.NATIVE_POLL_ONCE) { base -> base.copy(mainThread = mainThread) }
    }

    /** 保存 Matrix 已截取的外部 SIGQUIT trace。 */
    override fun onAnrTraceFile(path: String) {
        enqueueTrace(AnrReportType.ANR_TRACE, path)
    }

    /** 保存 Matrix 已截取的手动 trace。 */
    override fun onPrintTraceFile(path: String) {
        enqueueTrace(AnrReportType.MANUAL_TRACE, path)
    }

    /** 由保存线程读取 Matrix 已生成的文件，不修改其截取逻辑。 */
    private fun enqueueTrace(type: AnrReportType, path: String) {
        enqueue(type) { base ->
            /** 保留 Matrix 文件的原始字节，文本视图只用于直接阅读。 */
            val bytes = File(path).readBytes()
            base.copy(trace = bytes.toString(Charsets.UTF_8), traceBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
    }

    /** 异步提交 Matrix 回调；只有存储成功后才通知宿主。 */
    private fun enqueue(type: AnrReportType, populate: (AnrReport) -> AnrReport) {
        /** 回调发生时的事件时间，不在下次启动时重新生成。 */
        val occurredAt = System.currentTimeMillis()
        try {
            executor.execute {
                try {
                    /** 同次运行与其他功能共用的身份快照。 */
                    val identity = AnrReportIdentity(
                        context.anonymousDeviceId,
                        context.runtimeIdentity.sessionId,
                        context.runtimeIdentity.processId,
                        context.applicationMetadata.packageName,
                        context.applicationMetadata.versionName,
                        context.applicationMetadata.versionCode,
                        context.buildId,
                        context.environment,
                        context.channel,
                    )
                    /** Matrix 已发出的单次回调。 */
                    val report = populate(AnrReport(UUID.randomUUID().toString(), occurredAt, type, identity))
                    when (val saved = reports.save(report)) {
                        is AnrReportResult.Success -> {
                            Log.i(TAG, "Matrix ANR 结果已保存：$type")
                            runCatching { listener.get()?.onReportSaved(saved.value) }.onFailure {
                                Log.w(TAG, "ANR 报告回调失败：${it.javaClass.simpleName}")
                            }
                        }
                        is AnrReportResult.Failure -> Log.w(TAG, "Matrix ANR 结果保存失败：${saved.error}")
                    }
                } catch (failure: Exception) {
                    Log.w(TAG, "Matrix ANR 结果保存失败：${failure.javaClass.simpleName}")
                }
            }
        } catch (failure: RejectedExecutionException) {
            Log.w(TAG, "Matrix ANR 保存线程已关闭")
        }
    }

    /** 不包含原始报告的日志标签。 */
    private companion object {
        const val TAG = "PerformanceAnr"
    }
}
