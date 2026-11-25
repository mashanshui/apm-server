package com.shanshui.performance.anr

import android.app.Application
import android.os.Build
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shanshui.performance.anr.internal.SignalAnrTracer
import com.shanshui.performance.network.NetworkConfig
import com.shanshui.performance.network.TransportSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.io.File
import java.math.BigInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 在隔离的 arm64 设备中验证 Matrix 自发 SIGQUIT 链路。 */
@RunWith(AndroidJUnit4::class)
class MatrixSignalHandlerTest {
    /** 验证 Matrix 的 handler 安装、手动 trace 和关闭。 */
    @Test
    fun manualTraceUsesMatrixSignalPath() {
        assumeTrue(Build.VERSION.SDK_INT >= 23 && android.os.Process.is64Bit())
        assumeTrue(Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a")
        /** 设备测试进程中的宿主应用。 */
        val application = ApplicationProvider.getApplicationContext<Application>()
        /** 与宿主正常初始化一致，在主线程安装并恢复 Matrix 处理器。 */
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        /** 启动前 SIGQUIT 与 debugger 信号的处理器状态。 */
        val previousSignalHandlers = caughtSignals() and MATRIX_SIGNAL_MASK
        /** 保存主线程创建的 SDK 句柄。 */
        val handleRef = AtomicReference<AnrHandle>()
        instrumentation.runOnMainSync {
            handleRef.set(AnrComponent.start(application, AnrConfig(enabled = true)))
        }
        /** Matrix 采集和本地报告句柄。 */
        val handle = handleRef.get()
        /** 收集完成回调结果。 */
        val summaryRef = AtomicReference<AnrReportSummary>()
        val saved = CountDownLatch(1)
        try {
            assertTrue(handle.isAvailable)
            assertEquals(MATRIX_SIGNAL_MASK, caughtSignals() and MATRIX_SIGNAL_MASK)
            /** 相同配置的借用方关闭时，不应关闭原有所有者的 Matrix 采集器。 */
            val borrowedRef = AtomicReference<AnrHandle>()
            instrumentation.runOnMainSync {
                borrowedRef.set(AnrComponent.start(application, AnrConfig(enabled = true)))
            }
            val borrowed = borrowedRef.get()
            assertTrue(borrowed.isAvailable)
            instrumentation.runOnMainSync { borrowed.close() }
            assertTrue(handle.isAvailable)
            assertEquals(MATRIX_SIGNAL_MASK, caughtSignals() and MATRIX_SIGNAL_MASK)
            /** 不同配置必须返回冲突，且不改变活动采集器。 */
            val conflict = AnrComponent.start(application, AnrConfig(enabled = true, maxReportCount = 19))
            assertEquals(AnrInitStatus.FAILED, conflict.initResult.status)
            assertEquals("configuration_conflict", conflict.initResult.reason)
            conflict.close()
            handle.setReportListener { summary ->
                summaryRef.set(summary)
                saved.countDown()
                throw IllegalStateException("验证回调异常隔离")
            }
            instrumentation.runOnMainSync { assertTrue(handle.printTrace()) }
            assertTrue("Matrix 手动 trace 未保存", saved.await(20, TimeUnit.SECONDS))
            /** 回调必须在报告提交之后触发，并明确手动来源。 */
            val summary = summaryRef.get()
            assertEquals(AnrReportType.MANUAL_TRACE, summary.type)
            val report = (handle.reports.read(summary.id) as AnrReportResult.Success).value
            assertTrue(!report.trace.isNullOrEmpty())
            /** 已保存的 Base64 与脱敏后的 trace 文本一致，独立文件已被消费。 */
            assertArrayEquals(report.trace!!.toByteArray(Charsets.UTF_8), Base64.decode(report.traceBase64, Base64.NO_WRAP))
            val activeDir = File(application.noBackupFilesDir, "performance-anr/active")
            assertFalse(activeDir.listFiles().orEmpty().any { it.name.startsWith("manual.trace.") })
            assertEquals(application.packageName, report.identity.packageName)

            /** 损坏测试索引后再次触发采集，保存失败时不能发出成功回调。 */
            val index = File(application.noBackupFilesDir, "performance-anr/index.json")
            val previousIndex = index.readBytes()
            val unexpectedCallback = CountDownLatch(1)
            val traceCallback = CountDownLatch(1)
            /** 包装真实 trace 监听器，确认第二次回调已到达后再检查保存失败。 */
            val traceField = SignalAnrTracer::class.java.getDeclaredField("sTraceFileListener").apply { isAccessible = true }
            val originalTraceListener = traceField.get(null) as SignalAnrTracer.TraceFileListener
            try {
                index.writeText("{")
                handle.setReportListener { unexpectedCallback.countDown() }
                traceField.set(null, object : SignalAnrTracer.TraceFileListener {
                    /** 转交外部信号 trace。 */
                    override fun onAnrTraceFile(captureId: String, path: String, truncated: Boolean) {
                        originalTraceListener.onAnrTraceFile(captureId, path, truncated)
                    }
                    /** 转交手动 trace，并记录回调已到达。 */
                    override fun onPrintTraceFile(path: String, truncated: Boolean) {
                        originalTraceListener.onPrintTraceFile(path, truncated)
                        traceCallback.countDown()
                    }
                })
                instrumentation.runOnMainSync { assertTrue(handle.printTrace()) }
                assertTrue("第二次 Matrix trace 未触发", traceCallback.await(10, TimeUnit.SECONDS))
                assertFalse("保存失败不应发出成功回调", unexpectedCallback.await(2, TimeUnit.SECONDS))
            } finally {
                traceField.set(null, originalTraceListener)
                index.writeBytes(previousIndex)
            }
        } finally {
            instrumentation.runOnMainSync { handle.close() }
        }
        assertEquals(previousSignalHandlers, caughtSignals() and MATRIX_SIGNAL_MASK)
        /** 旧信号与 trace 已结束时可以在同一进程重新装配。 */
        val restartedRef = AtomicReference<AnrHandle>()
        /** 新会话借用共享网络资源，关闭 ANR 时不得关闭该资源。 */
        val borrowedSession = TransportSession.create(NetworkConfig("http://127.0.0.1:43111/", "test-only"))
        instrumentation.runOnMainSync { restartedRef.set(AnrComponent.start(application,
            AnrConfig(enabled = true, uploadEnabled = true), session = borrowedSession)) }
        val restarted = restartedRef.get()
        try {
            assertTrue(restarted.isAvailable)
            assertEquals(MATRIX_SIGNAL_MASK, caughtSignals() and MATRIX_SIGNAL_MASK)
            instrumentation.runOnMainSync { restarted.close() }
            assertEquals(previousSignalHandlers, caughtSignals() and MATRIX_SIGNAL_MASK)
            assertFalse(borrowedSession.okHttpClient.dispatcher.executorService.isShutdown)
        } finally {
            instrumentation.runOnMainSync { restarted.close() }
            borrowedSession.close()
        }
        /** 关闭采集器后仍可通过无需 JNI 的入口读取并删除报告。 */
        val id = summaryRef.get().id
        val reopened = AnrComponent.reports(application)
        assertTrue(reopened.read(id) is AnrReportResult.Success)
        assertTrue(reopened.delete(id) is AnrReportResult.Success)
    }

    /** 读取进程中已安装信号处理器的位掩码，不输出进程内容。 */
    private fun caughtSignals(): Long = File("/proc/self/status").useLines { lines ->
        /** Linux 提供的 SigCgt 十六进制位掩码。 */
        val value = lines.first { it.startsWith("SigCgt:") }.substringAfter(':').trim()
        BigInteger(value, 16).toLong()
    }

    /** Matrix 的 SIGQUIT 和 __SIGRTMIN + 3 信号位。 */
    private companion object {
        const val MATRIX_SIGNAL_MASK = (1L shl (3 - 1)) or (1L shl (35 - 1))
    }
}
