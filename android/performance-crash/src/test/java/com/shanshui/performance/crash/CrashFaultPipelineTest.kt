package com.shanshui.performance.crash

import com.shanshui.performance.network.CrashBatchResponse
import com.shanshui.performance.network.NetworkResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 将处理器、真实采集组合、裁剪、文件队列与重试联动验证。 */
class CrashFaultPipelineTest {
    /** 测试只使用独立临时队列。 */
    @get:Rule val temporaryFolder = TemporaryFolder()

    /** 权限失败、超时和超限同时发生时，脱敏核心仍可离线重试并只委托一次。 */
    @Test
    fun recoversRedactedCoreAcrossCombinedCollectionAndUploadFailures() = runBlocking {
        // 独立目录仅用于本次测试。
        val root = temporaryFolder.newFolder("combined-failures")
        // 传入处理器的测试崩溃线程。
        val crashed = Thread({}, "worker")
        // 允许采集为空栈的另一线程。
        val other = Thread({}, "other")
        // 异常消息包含需要清理的合成哨兵。
        val original = IllegalStateException("token=secret-value alice@example.com")
        original.stackTrace = arrayOf(StackTraceElement("com.example.Test", "run", "Test.kt", 1))
        // 批次大小使用受限测试配置。
        val service = serviceConfig()
        // 工厂负责真实异常映射与消息脱敏。
        val factory = CrashEventFactory(CrashReporterConfig("com.example", "1", 1, "build"), service,
            "session", "11111111-1111-4111-8111-111111111111", "device", CrashDeviceInfo("35", "synthetic"),
            { null }, { 1_726_000_000_000 }, { "joint-event" })
        // 使用真实文件队列承接核心与最终事件。
        val queue = FileCrashQueue(root)
        // 单工作线程执行辅助采集。
        val worker = Executors.newSingleThreadExecutor()
        // 排障日志用于检查不会包含哨兵。
        val logs = mutableListOf<String>()
        // 显式固定两个测试线程与它们的快照。
        val threadCollector = ThreadDiagnosticsCollector("com.example", allThreads = {
            mapOf(crashed to arrayOf(StackTraceElement("A", "run", "A.kt", 1)), other to emptyArray())
        }, mainThread = { other })
        // 环境采集只依赖合成进程和设备资料。
        val environment = EnvironmentDiagnosticsCollector(SyntheticSource(), CachedCrashEnvironment(
            123, "com.example", 1, null, 35, listOf("arm64-v8a"), "synthetic", "synthetic", "test"), { true })
        // 模拟无法及时退出的 events 输出流。
        val blocked = BlockingInput()
        // 三个缓冲区分别制造超长日志、权限失败与超时。
        val logcat = LogcatDiagnosticsCollector(starter = { command ->
            when (command[2]) {
                "main" -> FakeProcess(ByteArrayInputStream(("11-08 10:00:00.000 123 456 I Tag: token=secret-value " +
                    "x".repeat(4096) + "\n").toByteArray()))
                "system" -> throw SecurityException("synthetic permission failure")
                else -> FakeProcess(blocked)
            }
        })
        // 组合真实线程、环境和日志采集器。
        val collector = CrashDiagnosticsCollector(threadCollector, environment, logcat, 123, 35)
        // 协调器按受限批次完成核心保存及冻结。
        val coordinator = CrashCaptureCoordinator(queue, CrashEventBudget(16 * 1024), collector, worker, logs::add)
        // 统计原处理器收到的委托次数。
        val delegated = AtomicInteger()
        // 通过真实异常入口触发完整采集流程。
        val handler = CrashExceptionHandler(
            capture = { thread, tid, error -> assertTrue(coordinator.capture(factory.crash(error), thread, tid, error)) },
            delegate = { thread, error -> assertSame(crashed, thread); assertSame(original, error); delegated.incrementAndGet() },
            currentTid = { 456 },
        )
        try {
            handler.uncaughtException(crashed, original)
            // 从持久队列读取最终冻结事件。
            val frozen = queue.peekBatch(1).single().record.event
            // 读取本次冻结的诊断分区。
            val diagnostics = frozen.crash!!.diagnostics!!
            assertEquals(1, delegated.get())
            assertTrue(diagnostics.logcat!!.main!!.truncated)
            assertEquals("permission_denied", diagnostics.logcat!!.system!!.reason)
            assertEquals("timeout", diagnostics.logcat!!.events!!.reason)
            assertTrue(blocked.closed)
            assertEquals("joint-event", frozen.eventId)
            assertEquals(123, diagnostics.process!!.pid)
            assertEquals(456, diagnostics.crashedThread!!.systemTid)
            // 检查文件和安全日志中的脱敏效果。
            val disk = root.resolve("events/joint-event.json").readText()
            for (secret in listOf("secret-value", "alice@example.com", "private-file")) {
                assertFalse(disk.contains(secret))
                assertFalse(logs.any { it.contains(secret) })
            }

            // 模拟暂时离线，再重建队列并以 duplicate 确认原始冻结事件。
            // 首次发送返回可重试的隔离故障。
            val offline = uploader(queue, service) { NetworkResult.HttpError(503, null, true, 0) }
            assertEquals(1, offline.flush(maxBatches = 1).eventsRetried)
            // 重建队列模拟新进程读取原冻结事件。
            val restarted = FileCrashQueue(root)
            // 重试返回重复确认并核对事件内容未发生变化。
            val retry = uploader(restarted, service) { request ->
                assertEquals(frozen, request.events.single())
                NetworkResult.Success(CrashBatchResponse(requestId = request.requestId, duplicate = 1), 200)
            }
            assertEquals(1, retry.flush(maxBatches = 1).eventsAcknowledged)
            assertEquals(0, restarted.count())
        } finally {
            logcat.close()
            worker.shutdownNow()
        }
    }

    /** 受控 OOM 经真实处理和采集组合时保留核心，跳过昂贵日志并委托。 */
    @Test
    fun keepsCoreAndDelegatesWhenOomSkipsExpensiveCollection() {
        // 独立目录仅用于本次 OOM 测试。
        val root = temporaryFolder.newFolder("combined-oom")
        // 使用真实文件队列保存核心。
        val queue = FileCrashQueue(root)
        // 使用有界辅助采集工作线程。
        val worker = Executors.newSingleThreadExecutor()
        // 触发异常的合成线程。
        val crashed = Thread({}, "oom-worker")
        // 创建受控 OOM，不试图耗尽主机或设备内存。
        val original = OutOfMemoryError("synthetic memory pressure")
        // 检测是否错误启动了日志子进程。
        val calls = AtomicInteger()
        // 检测是否错误请求了全线程堆栈。
        val stackReads = AtomicInteger()
        // 统计原处理器的委托次数。
        val delegated = AtomicInteger()
        // 使用有限批次配置及合成凭据。
        val service = serviceConfig()
        // 为受控异常构造稳定的事件身份。
        val factory = CrashEventFactory(CrashReporterConfig("com.example", "1", 1, "build"), service,
            "session", "11111111-1111-4111-8111-111111111111", "device", CrashDeviceInfo("35", "synthetic"),
            { null }, { 1_726_000_000_000 }, { "oom-event" })
        // 若 OOM 路径误做全线程抓栈则记录调用。
        val threads = ThreadDiagnosticsCollector("com.example", allThreads = {
            stackReads.incrementAndGet()
            error("OOM 不应全量抓栈")
        })
        // OOM 下仍允许读取廉价 Runtime 数据及进程身份。
        val environment = EnvironmentDiagnosticsCollector(SyntheticSource(), CachedCrashEnvironment(
            123, "com.example", 1, null, 35, listOf("arm64-v8a"), "synthetic", "synthetic", "test"), { true })
        // 若 OOM 路径误启动 logcat 则记录并失败。
        val logcat = LogcatDiagnosticsCollector(starter = { calls.incrementAndGet(); error("OOM 不应启动 logcat") })
        // 使用生产组合采集器执行降级。
        val collector = CrashDiagnosticsCollector(threads, environment, logcat, 123, 35)
        // 保存冻结结果并限制批次大小。
        val coordinator = CrashCaptureCoordinator(queue, CrashEventBudget(16 * 1024), collector, worker)
        // 验证原线程与异常只委托一次。
        val handler = CrashExceptionHandler(
            capture = { thread, tid, error -> assertTrue(coordinator.capture(factory.crash(error), thread, tid, error)) },
            delegate = { thread, error -> assertSame(crashed, thread); assertSame(original, error); delegated.incrementAndGet() },
            currentTid = { 456 },
        )
        try {
            handler.uncaughtException(crashed, original)
            // 检查实际落盘的事件及保留身份。
            val frozen = queue.peekBatch(1).single().record.event
            assertEquals(1, delegated.get())
            assertEquals(0, calls.get())
            assertEquals(0, stackReads.get())
            assertEquals("java.lang.OutOfMemoryError", requireNotNull(frozen.crash!!.throwableChain).first().type)
            assertEquals("memory_pressure", frozen.crash!!.diagnostics!!.logcat!!.reason)
            assertEquals(123, frozen.crash!!.diagnostics!!.process!!.pid)
            assertEquals(456, frozen.crash!!.diagnostics!!.crashedThread!!.systemTid)
        } finally {
            logcat.close()
            worker.shutdownNow()
        }
    }

    /** 提供不包含真实凭据的有限批次配置。 */
    private fun serviceConfig() = CrashServiceConfig(
        baseUrl = "https://example.test/", appKey = "test-key", environment = "test", channel = "test",
        schemaVersion = 2, enableNetworkLogging = false, connectTimeoutMillis = 3000, readTimeoutMillis = 3000,
        writeTimeoutMillis = 3000, crashBatchSize = 20, crashMaxBatchBytes = 16 * 1024,
        crashUploadIntervalMillis = 30000,
    )

    /** 按固定时钟创建离线或确认响应的上传器。 */
    private fun uploader(queue: FileCrashQueue, config: CrashServiceConfig, sender: CrashBatchSender) = CrashUploader(
        queue = queue, sender = sender, config = config, clock = { 1_726_000_000_000 }, requestIdGenerator = { "request" },
    )

    /** 提供合成内存、proc 和私有 FD，不读取宿主真实信息。 */
    private class SyntheticSource : CrashEnvironmentSource {
        /** 返回合法的零值。 */ override fun runtimeHeap() = RuntimeHeapSnapshot(0, 1024)
        /** 返回固定内存值。 */ override fun debugMemory() = DebugMemorySnapshot(1, 2, 3, 4)
        /** 返回不会暴露业务内容的合成文本。 */ override fun readProc(path: String, maxBytes: Int) = BoundedProcText("synthetic", false)
        /** 返回一个合成文件描述符。 */ override fun fileDescriptors() = listOf(3)
        /** 模拟需要隐藏的私有文件名。 */ override fun fileTarget(fd: Int) = "/data/user/0/com.example/files/private-file"
    }

    /** 关闭时解除读取阻塞，模拟无法及时结束的 events 缓冲区。 */
    private class BlockingInput : InputStream() {
        /** 关闭门闩。 */ private val release = CountDownLatch(1)
        /** 是否收到关闭请求。 */ @Volatile var closed = false
        /** 等待超时清理。 */ override fun read(): Int { release.await(); return -1 }
        /** 块读取同样等待关闭。 */ override fun read(buffer: ByteArray, offset: Int, length: Int): Int = read()
        /** 释放阻塞中的读取。 */ override fun close() { closed = true; release.countDown() }
    }

    /** 受控 logcat 子进程，输出只来自合成流。 */
    private class FakeProcess(/** 受控输出。 */ private val input: InputStream) : Process() {
        /** 无需使用的输入流。 */ private val output = ByteArrayOutputStream()
        /** 合并错误后的空错误流。 */ private val error = ByteArrayInputStream(byteArrayOf())
        /** 返回输入端。 */ override fun getOutputStream(): OutputStream = output
        /** 返回受控日志。 */ override fun getInputStream(): InputStream = input
        /** 返回空错误流。 */ override fun getErrorStream(): InputStream = error
        /** 返回成功退出码。 */ override fun waitFor() = 0
        /** 返回成功退出码。 */ override fun exitValue() = 0
        /** 清理时关闭日志流。 */ override fun destroy() { input.close() }
    }
}
