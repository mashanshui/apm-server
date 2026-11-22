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
import com.shanshui.performance.core.PerformanceEventContextFactory
import com.shanshui.performance.core.PerformanceEventContext
import com.shanshui.performance.core.SharedMetadataConfig
import java.io.File
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
