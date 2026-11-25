package com.shanshui.performance

import android.app.Application
import android.app.Instrumentation
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shanshui.performance.anr.AnrComponent
import com.shanshui.performance.anr.AnrHandle
import com.shanshui.performance.anr.AnrInitStatus
import com.shanshui.performance.anr.AnrReportResult
import com.shanshui.performance.anr.AnrReportSummary
import com.shanshui.performance.anr.AnrReportType
import com.shanshui.performance.anr.internal.SignalAnrTracer
import com.shanshui.performance.core.PerformanceEventContextFactory
import com.shanshui.performance.core.PerformanceEventContext
import com.shanshui.performance.core.SharedMetadataConfig
import com.shanshui.performance.network.NetworkConfig
import java.io.File
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 验证全量 SDK 借用 ANR 时的真实生命周期和本地网络边界。 */
@RunWith(AndroidJUnit4::class)
class AnrSdkIntegrationTest {
    /** 仅装配 ANR 时可自行创建传输会话并上传已确认的事件。 */
    @Test
    fun standaloneAnrUploadsWithOwnedSession() {
        assumeTrue(Build.VERSION.SDK_INT >= 23 && android.os.Process.is64Bit())
        assumeTrue(Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a")
        /** 使用隔离 runner 和私有目录，避免触碰示例应用的既有报告。 */
        val application = ApplicationProvider.getApplicationContext<Application>()
        assumeTrue(application.javaClass == Application::class.java)
        /** 在主线程控制采集器生命周期。 */
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        /** 此用例专属的队列根目录。 */
        val sandbox = File(application.cacheDir, "anr-standalone-${UUID.randomUUID()}")
        assertTrue(sandbox.mkdirs())
        /** 保留宿主进程信息并隔离持久化目录。 */
        val isolated = TestRootApplication(application, sandbox)
        /** 按顺序接收本地服务捕获的请求。 */
        val requests = LinkedBlockingQueue<Pair<RecordedRequest, JSONObject>>()
        /** 本地服务根据实际批次 ID 返回有效确认。 */
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            /** 只在测试内读取请求，不输出事件正文。 */
            override fun dispatch(request: RecordedRequest): MockResponse {
                /** 解压后的请求只在测试进程内使用。 */
                val body = JSONObject(requestBody(request))
                requests.offer(request to body)
                return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                    .setBody(JSONObject().put("requestId", body.getString("requestId"))
                        .put("accepted", 1).put("rejected", 0).put("duplicate", 0)
                        .put("retryable", false).put("errors", org.json.JSONArray()).toString())
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        /** 地址解析在测试线程完成，采集器仍按要求在主线程启动。 */
        val networkConfig = NetworkConfig(server.url("/").toString(), "standalone-test-key")
        /** 跨线程保存独立采集句柄。 */
        val handleRef = AtomicReference<AnrHandle>()
        try {
            instrumentation.runOnMainSync {
                handleRef.set(AnrComponent.startWithNetwork(isolated,
                    AnrConfig(enabled = true, uploadEnabled = true), networkConfig))
            }
            /** 主线程已启动的独立采集器。 */
            val handle = handleRef.get()
            assertTrue("独立 ANR 未启动：${handle.initResult}", handle.isAvailable)
            /** 通过受控的已判定回调验证上传，不把它作为系统 ANR 证据。 */
            val listener = SignalAnrTracer::class.java.getDeclaredField("sSignalAnrDetectedListener")
                .apply { isAccessible = true }.get(null) as SignalAnrTracer.SignalAnrDetectedListener
            /** 只属于此测试事件的采集身份。 */
            val eventId = UUID.randomUUID().toString()
            listener.onAnrDetected(eventId, System.currentTimeMillis(), "demo.Block.main(Block.kt:1)",
                null, null, true, true, "", "受控测试系统说明")
            /** 捕获实际发出的公共批次。 */
            val request = requests.poll(20, TimeUnit.SECONDS)
            assertNotNull("独立 ANR 未上传", request)
            assertEquals("standalone-test-key", request!!.first.getHeader("X-App-Key"))
            assertEquals("gzip", request.first.getHeader("Content-Encoding"))
            assertEquals(eventId, request.second.getJSONArray("events").getJSONObject(0).getString("eventId"))
            /** 等待有效确认落地，再关闭该组件拥有的网络会话。 */
            val pending = File(sandbox, "performance-anr/pending/$eventId.json")
            /** 有界等待本地确认状态完成。 */
            val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
            while (pending.exists() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
            assertFalse("独立 ANR 确认后队列未清理", pending.exists())
        } finally {
            instrumentation.runOnMainSync { handleRef.get()?.close() }
            server.shutdown()
            sandbox.deleteRecursively()
        }
    }

    /** 全量 SDK 显式刷新可重试已冻结 ANR，且网络请求保持原身份和 gzip 编码。 */
    @Test
    fun fullSdkFlushRetriesFrozenAnrThroughSharedSession() {
        assumeTrue(Build.VERSION.SDK_INT >= 23 && android.os.Process.is64Bit())
        assumeTrue(Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a")
        /** 本用例使用隔离 runner，防止示例应用提前装配采集器。 */
        val application = ApplicationProvider.getApplicationContext<Application>()
        assumeTrue(application.javaClass == Application::class.java)
        /** 在主线程控制全量 SDK 生命周期。 */
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        /** 用独立目录避免改动其他仪器测试的报告及队列。 */
        val sandbox = File(application.cacheDir, "anr-upload-${UUID.randomUUID()}")
        assertTrue(sandbox.mkdirs())
        /** 保留真实进程信息并隔离持久化目录。 */
        val isolated = TestRootApplication(application, sandbox)
        /** 捕获两次实际请求及各自批次正文。 */
        val requests = LinkedBlockingQueue<Pair<RecordedRequest, JSONObject>>()
        /** 控制本地服务按请求顺序返回不同响应。 */
        val count = AtomicInteger()
        /** 收集请求并依次返回短暂退避的 503 和有效确认。 */
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            /** 请求正文只在测试中解压解析，响应中的批次 ID 取自实际请求。 */
            override fun dispatch(request: RecordedRequest): MockResponse {
                /** 解压后的请求只在测试进程内使用。 */
                val body = JSONObject(requestBody(request))
                requests.offer(request to body)
                if (count.incrementAndGet() == 1) {
                    return MockResponse().setResponseCode(503).setHeader("Retry-After", "1")
                        .setBody("""{"retryable":true}""")
                }
                return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                    .setBody(JSONObject().put("requestId", body.getString("requestId"))
                        .put("accepted", 1).put("rejected", 0).put("duplicate", 0)
                        .put("retryable", false).put("errors", org.json.JSONArray()).toString())
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        /** 只启用 ANR 上传，避免其他能力影响本次请求。 */
        val config = PerformanceConfig(
            service = ServiceConfig(baseUrl = server.url("/").toString(), buildId = "anr-test-build"),
            crash = CrashConfig(enabled = false),
            anr = AnrConfig(enabled = true, uploadEnabled = true, uploadIntervalMillis = 60_000),
            jank = JankConfig(enabled = false), fps = FpsConfig(enabled = false),
            memory = MemoryConfig(enabled = false), memoryLeak = MemoryLeakConfig(enabled = false),
        )
        /** 跨线程保存全量 SDK 句柄。 */
        val sdkRef = AtomicReference<PerformanceSdk>()
        try {
            instrumentation.runOnMainSync { sdkRef.set(PerformanceSdk.initialize(isolated, "local-test-app-key", config)) }
            /** 已完成启动的全量 SDK。 */
            val sdk = sdkRef.get()
            assertTrue("ANR 采集器未启动：${sdk.anrInitResult}", sdk.anr?.isAvailable == true)
            /** 注入 Matrix 已确认的回调，不将该受控回调当作系统 ANR 验收。 */
            val listenerField = SignalAnrTracer::class.java.getDeclaredField("sSignalAnrDetectedListener")
                .apply { isAccessible = true }
            /** 当前 SDK 安装的已确认 ANR 监听器。 */
            val listener = listenerField.get(null) as SignalAnrTracer.SignalAnrDetectedListener
            /** 只属于此测试事件的采集身份。 */
            val eventId = UUID.randomUUID().toString()
            listener.onAnrDetected(eventId, System.currentTimeMillis(), "demo.Block.main(Block.kt:1)",
                null, null, true, true, "", "受控测试系统说明")
            /** 首次冻结后经网络发出的批次。 */
            val first = requests.poll(20, TimeUnit.SECONDS)
            assertNotNull("冻结后没有发起首次上传", first)
            /** 首次请求中的不可变事件。 */
            val firstEvent = first!!.second.getJSONArray("events").getJSONObject(0)
            assertEquals(eventId, firstEvent.getString("eventId"))
            assertEquals("gzip", first.first.getHeader("Content-Encoding"))
            assertEquals("local-test-app-key", first.first.getHeader("X-App-Key"))
            /** 首次响应被持久化为可重试后，再调用全量 SDK 的显式刷新。 */
            val pending = File(sandbox, "performance-anr/pending/$eventId.json")
            /** 有界等待首次 503 对应的退避状态落盘。 */
            val retryDeadline = android.os.SystemClock.elapsedRealtime() + 5_000
            while (android.os.SystemClock.elapsedRealtime() < retryDeadline &&
                (!pending.isFile || runCatching { JSONObject(pending.readText()).optInt("attempts") }.getOrDefault(0) == 0)) {
                Thread.sleep(25)
            }
            assertTrue("503 未保留待重试项", pending.isFile)
            assertEquals(1, JSONObject(pending.readText()).getInt("attempts"))
            /** 等待服务端建议的退避时间，避免把提前刷新误判为失败。 */
            val nextAttempt = JSONObject(pending.readText()).getLong("nextAttemptAtMillis")
            while (System.currentTimeMillis() < nextAttempt) Thread.sleep(25)
            sdk.flushAsync()
            /** 显式刷新后经网络发出的第二次批次。 */
            val second = requests.poll(10, TimeUnit.SECONDS)
            assertNotNull("全量刷新未重试 ANR", second)
            /** 重试请求中的同一不可变事件。 */
            val secondEvent = second!!.second.getJSONArray("events").getJSONObject(0)
            assertEquals(firstEvent.toString(), secondEvent.toString())
            assertEquals(sdk.sessionId, secondEvent.getString("sessionId"))
            assertEquals(sdk.processId, secondEvent.getString("processId"))
            /** 有界等待有效确认后的本地删除。 */
            val ackDeadline = android.os.SystemClock.elapsedRealtime() + 5_000
            while (pending.exists() && android.os.SystemClock.elapsedRealtime() < ackDeadline) Thread.sleep(25)
            assertFalse("有效确认后队列未清理", pending.exists())
            assertEquals(2, count.get())
        } finally {
            instrumentation.runOnMainSync { sdkRef.get()?.close() }
            server.shutdown()
            sandbox.deleteRecursively()
        }
    }

    /** 全量关闭后外部句柄仍能采集；本地 trace 不进入既有队列或发送网络请求。 */
    @Test
    fun borrowedAnrSurvivesSdkCloseWithoutUploadingTrace() {
        assumeTrue(Build.VERSION.SDK_INT >= 23 && android.os.Process.is64Bit())
        assumeTrue(Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a")
        /** 设备测试专属应用及主线程调度器。 */
        val application = ApplicationProvider.getApplicationContext<Application>()
        // 普通示例测试使用 App 自动初始化 SDK；本用例仅在独立 runner 下运行。
        assumeTrue(application.javaClass == Application::class.java)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        /** 仅绑定设备回环地址的请求捕获服务，不连接外部服务。 */
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            /** 测试期间统一返回暂不可用，避免修改既有上传队列。 */
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(503)
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        /** 全量与独立入口使用相同的配置和公共身份。 */
        val config = PerformanceConfig(
            service = ServiceConfig(baseUrl = server.url("/").toString(), buildId = "anr-test-build"),
            crash = CrashConfig(enabled = true, nativeEnabled = true),
            anr = AnrConfig(enabled = true),
            jank = JankConfig(enabled = true, enableSyncSampling = false, enableAsyncSampling = true),
            fps = FpsConfig(enabled = false),
            memory = MemoryConfig(enabled = false),
            memoryLeak = MemoryLeakConfig(enabled = false),
        )
        val context = PerformanceEventContextFactory.create(
            application,
            SharedMetadataConfig(config.service.environment, config.service.channel, config.service.buildId),
        )
        /** 外部 ANR 所有者与全量 SDK 使用各自的关闭句柄。 */
        val externalRef = AtomicReference<AnrHandle>()
        val sdkRef = AtomicReference<PerformanceSdk>()
        val saved = LinkedBlockingQueue<AnrReportSummary>()
        try {
            verifyTraceDirectoryFailure(application, instrumentation, config.anr, context)
            instrumentation.runOnMainSync {
                externalRef.set(AnrComponent.start(application, config.anr, context))
                externalRef.get().setReportListener { summary -> saved.offer(summary) }
                sdkRef.set(PerformanceSdk.initialize(application, "local-test-app-key", config))
            }
            val external = externalRef.get()
            val sdk = sdkRef.get()
            assertTrue(external.isAvailable)
            assertTrue(sdk.anr?.isAvailable == true)
            assertEquals(context.runtimeIdentity.sessionId, sdk.sessionId)
            assertEquals(context.runtimeIdentity.processId, sdk.processId)
            assertTrue("Rhea 未启动，无法验证组合运行：${sdk.jankInitResult}", sdk.isJankAvailable)

            /** 保存前的 Crash 和 Jank 队列文件清单。 */
            val before = existingQueueFiles(application)
            instrumentation.runOnMainSync { assertTrue(external.printTrace()) }
            val first = saved.poll(20, TimeUnit.SECONDS)
            assertNotNull("没有收到首次报告回调", first)
            assertEquals(AnrReportType.MANUAL_TRACE, first!!.type)
            val report = (external.reports.read(first.id) as AnrReportResult.Success).value
            assertEquals(context.runtimeIdentity.sessionId, report.identity.sessionId)
            assertEquals(context.runtimeIdentity.processId, report.identity.processId)
            sdk.flushAsync()
            assertEquals(before, existingQueueFiles(application))

            /** 释放全量所有者后，外部所有者仍能触发 Matrix 原有手动 trace。 */
            instrumentation.runOnMainSync { sdk.close() }
            assertTrue(external.isAvailable)
            instrumentation.runOnMainSync { assertTrue(external.printTrace()) }
            val second = saved.poll(20, TimeUnit.SECONDS)
            assertNotNull("全量关闭后外部句柄未收到回调", second)
            assertEquals(AnrReportType.MANUAL_TRACE, second!!.type)
            assertTrue(external.reports.delete(first.id) is AnrReportResult.Success)
            assertTrue(external.reports.delete(second.id) is AnrReportResult.Success)

            /** Crash 启动自带 app_start；允许该请求，但不能包含 ANR 报告。 */
            val requests = mutableListOf<RecordedRequest>()
            server.takeRequest(2, TimeUnit.SECONDS)?.let { requests.add(it) }
            while (true) {
                val next = server.takeRequest(300, TimeUnit.MILLISECONDS) ?: break
                requests.add(next)
            }
            assertTrue("未捕获既有 app_start 请求，无法证明捕获服务有效", requests.any { requestBody(it).contains("app_start") })
            requests.forEach { request ->
                /** 只在测试进程内检查解压后的请求，不输出请求正文。 */
                val body = requestBody(request)
                assertFalse("出现 ANR 请求路径", request.path.orEmpty().contains("anr", ignoreCase = true))
                assertFalse("首次 ANR 报告进入请求", body.contains(first.id))
                assertFalse("第二次 ANR 报告进入请求", body.contains(second.id))
                assertFalse("手动 trace 进入请求", body.contains("MANUAL_TRACE"))
            }
            assertEquals(before, existingQueueFiles(application))
        } finally {
            instrumentation.runOnMainSync {
                sdkRef.get()?.close()
                externalRef.get()?.close()
            }
            server.shutdown()
        }
    }

    /** 仅比较 Crash 和 Jank 队列的文件路径，不读取或输出报告内容。 */
    private fun existingQueueFiles(application: Application): Set<String> {
        /** 两种既有队列的应用私有目录。 */
        val roots = listOf(
            File(application.noBackupFilesDir, "performance-crash-reporter/events"),
            File(application.noBackupFilesDir, "performance-jank-reporter"),
        )
        return roots.flatMap { root ->
            if (!root.exists()) emptyList() else root.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toList()
        }.toSet()
    }

    /** 按现有传输层的 gzip 编码读取测试请求正文。 */
    private fun requestBody(request: RecordedRequest): String {
        /** 复制缓冲区以便多次检查同一个请求。 */
        val input = request.body.clone().inputStream()
        return if (request.getHeader("Content-Encoding").equals("gzip", ignoreCase = true)) {
            GZIPInputStream(input).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else {
            input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }

    /** 使用独立测试目录制造初始化失败，不修改应用真实的 ANR 报告目录。 */
    private fun verifyTraceDirectoryFailure(
        application: Application,
        instrumentation: Instrumentation,
        config: AnrConfig,
        context: PerformanceEventContext,
    ) {
        /** 测试专属的无备份目录及无法作为目录使用的活动文件。 */
        val sandbox = File(application.cacheDir, "anr-init-failure-${UUID.randomUUID()}")
        val blocked = File(sandbox, "performance-anr/active")
        try {
            assertTrue(blocked.parentFile!!.mkdirs())
            blocked.writeText("not a directory")
            /** 使用真实宿主上下文，只替换本次测试的无备份目录。 */
            val isolated = TestRootApplication(application, sandbox)
            val failed = AtomicReference<AnrHandle>()
            instrumentation.runOnMainSync { failed.set(AnrComponent.start(isolated, config, context)) }
            assertEquals(AnrInitStatus.FAILED, failed.get().initResult.status)
            assertEquals("trace_directory_failed", failed.get().initResult.reason)
            assertFalse(failed.get().isAvailable)
            failed.get().close()
        } finally {
            sandbox.deleteRecursively()
        }
    }

    /** 保留真实进程信息，同时将本次失败注入限制在测试目录。 */
    private class TestRootApplication(
        base: Application,
        /** 只供本次失败注入使用的目录。 */
        private val testRoot: File,
    ) : Application() {
        init {
            attachBaseContext(base)
        }

        /** 返回测试专属目录，不访问应用真实报告目录。 */
        override fun getNoBackupFilesDir(): File = testRoot
    }
}
