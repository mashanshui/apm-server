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
import com.shanshui.performance.anr.AnrReportType
import com.shanshui.performance.anr.internal.SignalAnrTracer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 在隔离测试进程中验证 Matrix 采样卡住时的信号转交边界。 */
@RunWith(AndroidJUnit4::class)
class AnrSignalTimeoutTest {
    /** 外部 SIGQUIT 等待默认 20 秒后继续转交，保留 Matrix 原有处理顺序。 */
    @Test
    fun forwardsExternalSignalAfterJavaSamplingTimeout() {
        /** 独立 runner 避免示例 Application 提前启动同一采集器。 */
        val application = ApplicationProvider.getApplicationContext<Application>()
        assumeTrue(application.javaClass == Application::class.java)
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 23 && Process.is64Bit())
        assumeTrue(android.os.Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a")
        val queueAccessible = runCatching {
            Looper.getMainLooper().queue.javaClass.getDeclaredField("mMessages").apply { isAccessible = true }
        }.isSuccess
        assumeTrue("系统不允许反射消息队列", queueAccessible)
        /** 采集器及主线程调度器。 */
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val handleRef = AtomicReference<AnrHandle>()
        instrumentation.runOnMainSync { handleRef.set(AnrComponent.start(application, AnrConfig(enabled = true))) }
        val handle = handleRef.get()
        assertTrue(handle.isAvailable)
        /** 暂存 SDK 原有回调，只在本测试期间注入受控阻塞。 */
        val tracerField = AnrHandle::class.java.getDeclaredField("tracer").apply { isAccessible = true }
        val tracer = tracerField.get(handle) as SignalAnrTracer
        val listenerField = SignalAnrTracer::class.java.getDeclaredField("sSignalAnrDetectedListener").apply { isAccessible = true }
        val originalListener = listenerField.get(null) as SignalAnrTracer.SignalAnrDetectedListener
        val timeoutField = SignalAnrTracer::class.java.getDeclaredField("anrReportTimeout").apply { isAccessible = true }
        assertEquals(20_000L, timeoutField.getLong(null))
        /** 控制主线程、阻塞的 Java 回调以及最终 trace 保存。 */
        val mainHandler = Handler(Looper.getMainLooper())
        val mainEntered = CountDownLatch(1)
        val releaseMain = CountDownLatch(1)
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val callbackExited = CountDownLatch(1)
        val traceSaved = CountDownLatch(1)
        try {
            tracer.setSignalAnrDetectedListener(object : SignalAnrTracer.SignalAnrDetectedListener {
                /** 故意阻塞 Matrix 的 Java 采样线程，不改变生产采集代码。 */
                override fun onAnrDetected(stack: String, message: String, whenMillis: Long, fromError: Boolean, cgroup: String) {
                    callbackEntered.countDown()
                    releaseCallback.await(30, TimeUnit.SECONDS)
                    callbackExited.countDown()
                }

                /** 此场景不模拟 debugger 信号。 */
                override fun onNativeBacktraceDetected(stack: String, message: String, whenMillis: Long, fromError: Boolean) = Unit

                /** 此场景不依赖死锁解析回调。 */
                override fun onDeadLockAnrDetected(
                    mainThread: String,
                    lockThread1: String,
                    lockThread2: String,
                    waitingList: MutableMap.MutableEntry<IntArray, Array<String>>,
                ) = Unit

                /** 此场景不依赖 nativePollOnce 回调。 */
                override fun onMainThreadStuckAtNativePollOnce(mainThread: String) = Unit
            })
            handle.setReportListener { summary ->
                if (summary.type == AnrReportType.ANR_TRACE) traceSaved.countDown()
            }
            mainHandler.post {
                mainEntered.countDown()
                releaseMain.await(30, TimeUnit.SECONDS)
            }
            assertTrue("主线程阻塞任务未开始", mainEntered.await(5, TimeUnit.SECONDS))
            /** 超过前后台阈值的消息使原 Matrix 走立即回调。 */
            val whenMillis = (SystemClock.uptimeMillis() - 15_000L).coerceAtLeast(1L)
            assertTrue(mainHandler.sendMessageAtTime(Message.obtain(mainHandler, TEST_MESSAGE), whenMillis))
            /** 子 shell 与目标是不同 PID，触发 Matrix 的外部 SIGQUIT 路径。 */
            val started = SystemClock.elapsedRealtime()
            val sender = java.lang.ProcessBuilder("/system/bin/sh", "-c", "kill -3 ${Process.myPid()}").start()
            assertEquals(0, sender.waitFor())
            assertTrue("Matrix 未进入 Java 采样回调", callbackEntered.await(5, TimeUnit.SECONDS))
            assertFalse("默认超时前不应完成 trace 转交", traceSaved.await(2, TimeUnit.SECONDS))
            assertTrue("Java 采样超时后没有保存转交的 trace", traceSaved.await(25, TimeUnit.SECONDS))
            assertTrue("Signal Catcher 在默认超时前被转交", SystemClock.elapsedRealtime() - started >= 19_000L)
            assertEquals("Java 回调仍应处于阻塞状态", 1L, callbackExited.count)
        } finally {
            releaseCallback.countDown()
            releaseMain.countDown()
            mainHandler.removeMessages(TEST_MESSAGE)
            if (callbackEntered.count == 0L) callbackExited.await(5, TimeUnit.SECONDS)
            tracer.setSignalAnrDetectedListener(originalListener)
            instrumentation.runOnMainSync { handle.close() }
        }
    }

    /** 专用于构造主线程过期消息的编号。 */
    private companion object {
        const val TEST_MESSAGE = 42_425
    }
}
