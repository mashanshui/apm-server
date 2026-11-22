package com.shanshui.performance

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shanshui.performance.anr.AnrComponent
import com.shanshui.performance.anr.AnrHandle
import com.shanshui.performance.anr.AnrReportResult
import com.shanshui.performance.anr.AnrReportSummary
import com.shanshui.performance.anr.AnrReportType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 在独立测试进程验证真机 debugger 信号和非 ANR 信号。 */
@RunWith(AndroidJUnit4::class)
class AnrPhysicalSignalTest {
    /** 外部 debugger 信号在主线程阻塞时保留 Matrix 的 Java 栈来源。 */
    @Test
    fun debuggerSignalPreservesJavaStackSource() {
        /** 使用独立 Application，避免示例启动时提前安装采集器。 */
        val application = ApplicationProvider.getApplicationContext<Application>()
        assumeTrue(application.javaClass == Application::class.java)
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 23 && Process.is64Bit())
        /** Matrix 的原判断依赖主线程消息队列字段。 */
        val queueAccessible = runCatching {
            Looper.getMainLooper().queue.javaClass.getDeclaredField("mMessages").apply { isAccessible = true }
        }.isSuccess
        assumeTrue("系统不允许反射消息队列", queueAccessible)
        /** 在测试进程主线程按宿主方式启动采集器。 */
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val handleRef = AtomicReference<AnrHandle>()
        instrumentation.runOnMainSync { handleRef.set(AnrComponent.start(application, AnrConfig(enabled = true))) }
        val handle = handleRef.get()
        assertTrue(handle.isAvailable)
        /** 通过消息队列构造 Matrix 原阈值可识别的受控阻塞。 */
        val mainHandler = Handler(Looper.getMainLooper())
        val mainEntered = CountDownLatch(1)
        val releaseMain = CountDownLatch(1)
        val saved = CountDownLatch(1)
        val summary = AtomicReference<AnrReportSummary>()
        try {
            verifyNonAnrSignal(handle)
            handle.setReportListener { report ->
                if (report.type == AnrReportType.DEBUGGER_SIGNAL) {
                    summary.set(report)
                    saved.countDown()
                }
            }
            mainHandler.post {
                mainEntered.countDown()
                releaseMain.await(25, TimeUnit.SECONDS)
            }
            assertTrue("主线程阻塞未开始", mainEntered.await(5, TimeUnit.SECONDS))
            /** 旧消息使前后台阈值均满足，不改动 Matrix 判断。 */
            val whenMillis = (SystemClock.uptimeMillis() - 15_000L).coerceAtLeast(1L)
            assertTrue(mainHandler.sendMessageAtTime(Message.obtain(mainHandler, TEST_MESSAGE), whenMillis))
            /** 子 shell 与目标进程同 UID、不同 PID；35 为 Matrix 使用的 debugger 信号。 */
            val sender = java.lang.ProcessBuilder("/system/bin/sh", "-c", "kill -35 ${Process.myPid()}").start()
            assertEquals("debugger 信号发送失败", 0, sender.waitFor())
            assertTrue("未保存 debugger 分支报告", saved.await(15, TimeUnit.SECONDS))
            val result = summary.get()
            assertNotNull(result)
            /** 该分支保存的是主线程 Java 栈，不能伪称新采集的 Native trace。 */
            val report = (handle.reports.read(result.id) as AnrReportResult.Success).value
            assertTrue(report.javaStack.orEmpty().contains("AnrPhysicalSignalTest"))
            assertTrue(report.trace.isNullOrEmpty())
            assertEquals(false, report.fromProcessErrorState)
            assertTrue(handle.reports.delete(result.id) is AnrReportResult.Success)
            /** 等待 Matrix 原有的两秒 handler 恢复窗口结束。 */
            Thread.sleep(2_500)
        } finally {
            releaseMain.countDown()
            mainHandler.removeMessages(TEST_MESSAGE)
            instrumentation.runOnMainSync { handle.close() }
        }
    }

    /** 向受控测试进程发送 SIGCONT，不应被误判成 ANR。 */
    private fun verifyNonAnrSignal(handle: AnrHandle) {
        /** 任意新增报告都会触发测试失败，历史报告不参与比较。 */
        val reported = CountDownLatch(1)
        handle.setReportListener { reported.countDown() }
        /** SIGCONT 对正在运行的测试进程无终止作用。 */
        val sender = java.lang.ProcessBuilder("/system/bin/sh", "-c", "kill -CONT ${Process.myPid()}").start()
        assertEquals("非 ANR 信号发送失败", 0, sender.waitFor())
        assertFalse("非 ANR 信号不应产生报告", reported.await(3, TimeUnit.SECONDS))
        assertTrue("非 ANR 信号后采集器仍应可用", handle.isAvailable)
    }

    /** 仅用于构造主线程过期消息的编号。 */
    private companion object {
        const val TEST_MESSAGE = 42_426
    }
}
