package com.shanshui.performance

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shanshui.performance.anr.AnrComponent
import com.shanshui.performance.anr.AnrHandle
import com.shanshui.performance.anr.AnrReportResult
import com.shanshui.performance.anr.AnrReportSummary
import com.shanshui.performance.anr.AnrReportType
import com.shanshui.performance.anr.internal.SignalAnrTracer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 显式诊断连续 Matrix trace 是否与每次 SDK 报告正确对应。 */
@RunWith(AndroidJUnit4::class)
class AnrConsecutiveTraceTest {
    /** 归档线程被回调阻塞时，后续报告仍应保留各自原始 trace。 */
    @Test
    fun eachReportRetainsItsOwnTrace() {
        /** 此诊断只在显式传参时运行，避免常规设备回归多次触发系统 trace。 */
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyTraceRace") == "true")
        val application = ApplicationProvider.getApplicationContext<Application>()
        assumeTrue(application.javaClass == Application::class.java)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val handleRef = AtomicReference<AnrHandle>()
        instrumentation.runOnMainSync { handleRef.set(AnrComponent.start(application, AnrConfig(enabled = true))) }
        val handle = handleRef.get()
        assertTrue(handle.isAvailable)
        /** 测试包装仅观测 SDK 已接受的 Matrix 文件回调。 */
        val tracerField = AnrHandle::class.java.getDeclaredField("tracer").apply { isAccessible = true }
        val tracer = tracerField.get(handle) as SignalAnrTracer
        val listenerField = SignalAnrTracer::class.java.getDeclaredField("sTraceFileListener").apply { isAccessible = true }
        val original = listenerField.get(null) as SignalAnrTracer.TraceFileListener
        val queued = LinkedBlockingQueue<String>()
        val saved = LinkedBlockingQueue<AnrReportSummary>()
        val callbacks = AtomicInteger()
        val releaseFirstCallback = CountDownLatch(1)
        /** 第二、第三次 trace 使用不同的线程标记。 */
        val releaseFirstMarker = CountDownLatch(1)
        val releaseSecondMarker = CountDownLatch(1)
        val markerOneReady = CountDownLatch(1)
        val markerTwoReady = CountDownLatch(1)
        val markerOne = Thread({ markerOneReady.countDown(); releaseFirstMarker.await(30, TimeUnit.SECONDS) }, MARKER_ONE)
        val markerTwo = Thread({ markerTwoReady.countDown(); releaseSecondMarker.await(30, TimeUnit.SECONDS) }, MARKER_TWO)
        try {
            tracer.setTraceFileListener(object : SignalAnrTracer.TraceFileListener {
                /** 保持 SDK 原有 ANR trace 回调。 */
                override fun onAnrTraceFile(captureId: String, path: String, truncated: Boolean) =
                    original.onAnrTraceFile(captureId, path, truncated)

                /** 原回调完成入队后通知测试线程。 */
                override fun onPrintTraceFile(path: String, truncated: Boolean) {
                    original.onPrintTraceFile(path, truncated)
                    queued.offer(path)
                }
            })
            handle.setReportListener { summary ->
                if (summary.type == AnrReportType.MANUAL_TRACE) {
                    saved.offer(summary)
                    if (callbacks.getAndIncrement() == 0) releaseFirstCallback.await(30, TimeUnit.SECONDS)
                }
            }
            /** 第一份报告已保存后，故意阻塞其宿主完成回调。 */
            instrumentation.runOnMainSync { assertTrue(handle.printTrace()) }
            assertNotNull("首次 Matrix 回调未入队", queued.poll(20, TimeUnit.SECONDS))
            assertNotNull("首次报告未保存", saved.poll(20, TimeUnit.SECONDS))

            markerOne.start()
            assertTrue(markerOneReady.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { assertTrue(handle.printTrace()) }
            val secondPath = queued.poll(20, TimeUnit.SECONDS)
            assertNotNull("第二次 Matrix 回调未入队", secondPath)
            assertTrue("第二次封存 trace 缺少对应线程", File(secondPath!!).readText().contains(MARKER_ONE))
            releaseFirstMarker.countDown()
            markerOne.join(5_000)

            markerTwo.start()
            assertTrue(markerTwoReady.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { assertTrue(handle.printTrace()) }
            val thirdPath = queued.poll(20, TimeUnit.SECONDS)
            assertNotNull("第三次 Matrix 回调未入队", thirdPath)
            assertTrue("第三次封存 trace 缺少对应线程", File(thirdPath!!).readText().contains(MARKER_TWO))
            releaseFirstCallback.countDown()
            /** 分别读取已保存的第二和第三份报告，不输出 trace 内容。 */
            val second = saved.poll(20, TimeUnit.SECONDS)
            val third = saved.poll(20, TimeUnit.SECONDS)
            assertNotNull("第二份报告未保存", second)
            assertNotNull("第三份报告未保存", third)
            val secondTrace = (handle.reports.read(second!!.id) as AnrReportResult.Success).value.trace.orEmpty()
            val thirdTrace = (handle.reports.read(third!!.id) as AnrReportResult.Success).value.trace.orEmpty()
            assertTrue("第三份报告不对应第三次 trace", thirdTrace.contains(MARKER_TWO))
            assertFalse("第二份报告错误读取了第三次 trace", secondTrace.contains(MARKER_TWO))
            assertTrue("第二份报告未保留第二次 trace", secondTrace.contains(MARKER_ONE))
        } finally {
            releaseFirstCallback.countDown()
            releaseFirstMarker.countDown()
            releaseSecondMarker.countDown()
            markerOne.join(5_000)
            markerTwo.join(5_000)
            tracer.setTraceFileListener(original)
            instrumentation.runOnMainSync { handle.close() }
        }
    }

    /** 能在 Android 线程 trace 中区分的短标记。 */
    private companion object {
        const val MARKER_ONE = "ANR_MARK_A"
        const val MARKER_TWO = "ANR_MARK_B"
    }
}
