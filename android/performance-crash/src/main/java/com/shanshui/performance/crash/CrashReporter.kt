package com.shanshui.performance.crash

import android.content.Context
import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.util.Log
import com.shanshui.performance.core.AndroidNetworkTypeProvider
import com.shanshui.performance.core.DeviceIdentityStore
import com.shanshui.performance.identity.RuntimeIdentity
import com.shanshui.performance.network.CrashNetworkClientFactory
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

internal class CrashReporter private constructor(
    private val context: Context,
    private val config: CrashReporterConfig,
    private val serviceConfig: CrashServiceConfig,
    private val queue: FileCrashQueue,
    private val eventFactory: CrashEventFactory,
    private val uploader: CrashUploader,
    private val scheduler: ScheduledExecutorService,
    /** 核心持久化与有界辅助采集协调器。 */
    private val captureCoordinator: CrashCaptureCoordinator,
    /** 预建的唯一诊断工作线程。 */
    private val diagnosticWorker: ThreadPoolExecutor,
    /** 日志子进程的生命周期。 */
    private val logcatCollector: LogcatDiagnosticsCollector,
    /** 可选前后台状态监听。 */
    private val foregroundTracker: CrashForegroundTracker?,
    private val previousExceptionHandler: Thread.UncaughtExceptionHandler?,
) : Closeable {
    /** 后台和处理器上传复用同一个非等待互斥门。 */
    private val flushGate = CrashFlushGate()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** 只在发生异常的线程取得系统 TID，不能用 Java 线程 ID 代替。 */
    private val uncaughtExceptionHandler = CrashExceptionHandler(
        capture = ::captureAndFlush,
        delegate = ::delegateToPreviousHandler,
        currentTid = { android.os.Process.myTid() },
    )

    init {
        start()
    }

    fun flushAsync() {
        if (scheduler.isShutdown) {
            return
        }
        scheduler.execute { flushInBackground() }
    }

    fun pendingEventCount(): Int = queue.count()

    override fun close() {
        unregisterNetworkCallback()
        runCatching { foregroundTracker?.close() }
        diagnosticWorker.shutdownNow()
        logcatCollector.close()
        scheduler.shutdownNow()
        if (Thread.getDefaultUncaughtExceptionHandler() === uncaughtExceptionHandler) {
            Thread.setDefaultUncaughtExceptionHandler(previousExceptionHandler)
        }
    }

    private fun start() {
        // 必须先恢复上一进程尚未冻结的核心记录，再安排任何上传。
        val recovered = try {
            queue.recoverCaptures()
        } catch (failure: Throwable) {
            Log.w(TAG, "crash capture recovery failed")
            throw failure
        }
        Log.i(TAG, "crash capture recovery complete: count=$recovered")
        runCatching {
            queue.enqueue(eventFactory.appStart())
        }.onFailure {
            log("unable to persist app_start: ${it.javaClass.simpleName}")
        }
        Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler)
        registerNetworkRecovery()
        scheduler.schedule({ flushInBackground() }, 0, TimeUnit.MILLISECONDS)
        scheduler.scheduleAtFixedRate(
            { flushInBackground() },
            serviceConfig.crashUploadIntervalMillis,
            serviceConfig.crashUploadIntervalMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    /** 冻结的事件才能交给现有同步上传流程；失败时保留已持久化核心。 */
    private fun captureAndFlush(thread: Thread, systemTid: Int?, throwable: Throwable) {
        runCatching {
            val event = eventFactory.crash(throwable)
            if (captureCoordinator.capture(event, thread, systemTid, throwable)) flushSynchronously()
        }.onFailure {
            log("unable to persist or upload crash")
        }
    }

    private fun flushInBackground() {
        flushGate.runIfAvailable {
            try {
                runBlocking {
                    uploader.flush()
                }
            } catch (throwable: Throwable) {
                log("background crash upload failed: ${throwable.javaClass.simpleName}")
            }
        }
    }

    private fun flushSynchronously() {
        flushGate.runIfAvailable {
            runBlocking {
                withTimeout(CRASH_HANDLER_FLUSH_TIMEOUT_MILLIS) {
                    uploader.flush(maxBatches = 1)
                }
            }
        }
    }

    private fun delegateToPreviousHandler(thread: Thread, throwable: Throwable) {
        try {
            previousExceptionHandler?.uncaughtException(thread, throwable)
                ?: Runtime.getRuntime().exit(1)
        } catch (_: Throwable) {
            Runtime.getRuntime().exit(1)
        }
    }

    private fun registerNetworkRecovery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return
        }
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                flushAsync()
            }
        }
        runCatching {
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }.onFailure {
            log("unable to register network callback: ${it.javaClass.simpleName}")
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        runCatching { manager.unregisterNetworkCallback(callback) }
        networkCallback = null
    }

    private fun log(message: String) {
        Log.w(TAG, message)
    }

    companion object {
        private const val TAG = "CrashReporter"
        internal const val QUEUE_DIRECTORY = "performance-crash-reporter"
        private const val CRASH_HANDLER_FLUSH_TIMEOUT_MILLIS = 3_500L

        /** 由 PerformanceSdk 调用，使用统一初始化阶段创建 Crash Reporter。 */
        internal fun start(
            context: Context,
            config: CrashReporterConfig,
            serviceConfig: CrashServiceConfig,
            networkFactory: CrashNetworkClientFactory,
            runtimeIdentity: RuntimeIdentity,
            anonymousDeviceId: String,
        ): CrashReporter {
            val applicationContext = context.applicationContext
            val queueRoot = File(applicationContext.noBackupFilesDir, QUEUE_DIRECTORY)
            val queue = FileCrashQueue(queueRoot)
            val eventFactory = CrashEventFactory(
                config = config,
                serviceConfig = serviceConfig,
                sessionId = runtimeIdentity.sessionId,
                processId = runtimeIdentity.processId,
                anonymousDeviceId = anonymousDeviceId,
                deviceInfo = CrashDeviceInfo(
                    osVersion = Build.VERSION.RELEASE.orEmpty().ifBlank { "unknown" },
                    deviceModel = Build.MODEL.orEmpty().ifBlank { "unknown" },
                ),
                networkTypeProvider = AndroidNetworkTypeProvider(applicationContext),
            )
            val uploader = CrashUploader(
                queue = queue,
                sender = CrashBatchSender { request ->
                    networkFactory.crashNetworkClient.sendBatch(request)
                },
                config = serviceConfig,
                logger = { message -> Log.w(TAG, message) },
            )
            val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "crash-upload").apply { isDaemon = true }
            }
            val previousExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
            var diagnosticWorker: ThreadPoolExecutor? = null
            var logcatCollector: LogcatDiagnosticsCollector? = null
            var foregroundTracker: CrashForegroundTracker? = null
            return try {
                // 诊断工作者在初始化阶段启动；崩溃期间不反复创建替代线程。
                val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1)) { runnable ->
                    Thread(runnable, "crash-diagnostics").apply { isDaemon = true }
                }.apply { prestartCoreThread() }
                diagnosticWorker = worker
                val logs = LogcatDiagnosticsCollector()
                logcatCollector = logs
                val tracker = (applicationContext as? Application)?.let { application ->
                    runCatching { CrashForegroundTracker(application) }
                        .onFailure { Log.w(TAG, "crash foreground tracking unavailable") }.getOrNull()
                }
                foregroundTracker = tracker
                val cached = AndroidCrashEnvironmentSource.cache(System.currentTimeMillis())
                val environment = EnvironmentDiagnosticsCollector(AndroidCrashEnvironmentSource(), cached, { tracker?.current() })
                val collector = CrashDiagnosticsCollector(ThreadDiagnosticsCollector(config.applicationPackage), environment, logs,
                    cached.pid, cached.apiLevel)
                val coordinator = CrashCaptureCoordinator(queue, CrashEventBudget(serviceConfig.crashMaxBatchBytes), collector,
                    worker, logger = { message -> Log.i(TAG, message) })
                CrashReporter(
                    context = applicationContext,
                    config = config,
                    serviceConfig = serviceConfig,
                    queue = queue,
                    eventFactory = eventFactory,
                    uploader = uploader,
                    scheduler = scheduler,
                    captureCoordinator = coordinator,
                    diagnosticWorker = worker,
                    logcatCollector = logs,
                    foregroundTracker = tracker,
                    previousExceptionHandler = previousExceptionHandler,
                )
            } catch (throwable: Throwable) {
                runCatching { foregroundTracker?.close() }
                diagnosticWorker?.shutdownNow()
                logcatCollector?.close()
                scheduler.shutdownNow()
                if (Thread.getDefaultUncaughtExceptionHandler() !== previousExceptionHandler) {
                    Thread.setDefaultUncaughtExceptionHandler(previousExceptionHandler)
                }
                throw throwable
            }
        }

        /** Crash/Jank 共用的持久匿名设备标识，仍沿用 Crash 队列目录以保留已有身份。 */
        internal fun loadAnonymousDeviceId(context: Context): String {
            val applicationContext = context.applicationContext
            val queueRoot = File(applicationContext.noBackupFilesDir, QUEUE_DIRECTORY)
            return DeviceIdentityStore(File(queueRoot, "anonymous-device-id")).getOrCreate()
        }

    }
}
