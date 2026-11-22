package com.shanshui.performance.anr

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shanshui.performance.anr.internal.SignalAnrTracer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 在真实 Android 线程上验证 Matrix Java 采样及超时顺序。 */
@RunWith(AndroidJUnit4::class)
class AnrJavaSamplingTest {
    /** 主线程阻塞时保留原字段，回调未返回也不能让 Java 采样等待超过配置超时。 */
    @Test
    fun capturesMainThreadAndTimesOutWhileListenerIsBlocked() {
        /** 系统禁止反射队列字段时，Matrix 原逻辑会退回错误状态检查。 */
        val queueAccessible = runCatching {
            Looper.getMainLooper().queue.javaClass.getDeclaredField("mMessages").apply { isAccessible = true }
        }.isSuccess
        assumeTrue("系统不允许反射消息队列", queueAccessible)
        /** 当前测试应用和主线程 Handler。 */
        val application = ApplicationProvider.getApplicationContext<Application>()
        val mainHandler = Handler(Looper.getMainLooper())
        /** 控制主线程、Matrix 回调及测试线程的同步门闩。 */
        val mainEntered = CountDownLatch(1)
        val releaseMain = CountDownLatch(1)
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val callbackExited = CountDownLatch(1)
        /** Matrix 回调真实产生的字段。 */
        val captured = AtomicReference<Sample>()
        /** 在非主线程调用 Matrix 的 Java 入口。 */
        val executor = Executors.newSingleThreadExecutor()
        val tracer = SignalAnrTracer(application)
        /** Matrix 的默认采样等待时间。 */
        val timeoutField = SignalAnrTracer::class.java.getDeclaredField("anrReportTimeout").apply { isAccessible = true }
        val originalTimeout = timeoutField.getLong(null)
        try {
            assertEquals(20_000L, originalTimeout)
            tracer.setSignalAnrDetectedListener(object : SignalAnrTracer.SignalAnrDetectedListener {
                /** 保存 Matrix 实际字段并保持回调阻塞，供超时测试使用。 */
                override fun onAnrDetected(stack: String, message: String, whenMillis: Long, fromError: Boolean, cgroup: String) {
                    captured.set(Sample(stack, message, whenMillis, fromError, cgroup, Thread.currentThread().name))
                    callbackEntered.countDown()
                    releaseCallback.await(5, TimeUnit.SECONDS)
                    callbackExited.countDown()
                }

                /** 本用例不应走 debugger 分支。 */
                override fun onNativeBacktraceDetected(stack: String, message: String, whenMillis: Long, fromError: Boolean) = Unit

                /** 本用例不解析死锁 trace。 */
                override fun onDeadLockAnrDetected(
                    mainThread: String,
                    lockThread1: String,
                    lockThread2: String,
                    waitingList: MutableMap.MutableEntry<IntArray, Array<String>>,
                ) = Unit

                /** 本用例不解析 nativePollOnce。 */
                override fun onMainThreadStuckAtNativePollOnce(mainThread: String) = Unit
            })
            mainHandler.post {
                mainEntered.countDown()
                releaseMain.await(10, TimeUnit.SECONDS)
            }
            assertTrue("主线程阻塞任务未开始", mainEntered.await(5, TimeUnit.SECONDS))
            /** 放入超过前后台阈值的测试消息，保持主线程堆栈稳定。 */
            val whenMillis = (SystemClock.uptimeMillis() - 15_000L).coerceAtLeast(1L)
            assertTrue(mainHandler.sendMessageAtTime(Message.obtain(mainHandler, TEST_MESSAGE), whenMillis))
            val expectedStack = Looper.getMainLooper().thread.stackTrace.joinToString(separator = "\n", postfix = "\n")
            val expectedCgroup = File("/proc/self/cgroup").readText()
            SignalAnrTracer.setAnrReportTimeout(TEST_TIMEOUT_MILLIS)
            val entry = SignalAnrTracer::class.java.getDeclaredMethod("onANRDumped").apply { isAccessible = true }
            val duration = executor.submit<Long> {
                val started = SystemClock.elapsedRealtime()
                entry.invoke(null)
                SystemClock.elapsedRealtime() - started
            }
            assertTrue("Matrix 未触发非错误状态回调", callbackEntered.await(5, TimeUnit.SECONDS))
            val sample = captured.get()
            assertEquals("ANR-Dump-Thread", sample.threadName)
            assertEquals(expectedStack, sample.javaStack)
            assertEquals(expectedCgroup, sample.cgroup)
            assertTrue(sample.message.isNotEmpty())
            assertTrue(sample.messageWhenMillis < -10_000L)
            assertFalse(sample.fromProcessErrorState)
            assertTrue("Java 入口没有等待配置的超时", duration.get(5, TimeUnit.SECONDS) >= TEST_TIMEOUT_MILLIS - 40)
            assertEquals("回调仍被阻塞时不应提前结束", 1L, callbackExited.count)
        } finally {
            releaseCallback.countDown()
            releaseMain.countDown()
            mainHandler.removeMessages(TEST_MESSAGE)
            if (callbackEntered.count == 0L) callbackExited.await(5, TimeUnit.SECONDS)
            tracer.setSignalAnrDetectedListener(null)
            SignalAnrTracer.setAnrReportTimeout(originalTimeout)
            executor.shutdownNow()
        }
    }

    /** 保存一次真实 Java 采样回调，避免输出堆栈和消息正文。 */
    private data class Sample(
        /** 主线程 Java 栈。 */
        val javaStack: String,
        /** Matrix 读取的消息文本。 */
        val message: String,
        /** 消息时间差。 */
        val messageWhenMillis: Long,
        /** 是否通过系统错误状态确认。 */
        val fromProcessErrorState: Boolean,
        /** Matrix 读取的原始 cgroup。 */
        val cgroup: String,
        /** Matrix 执行采样的线程名。 */
        val threadName: String,
    )

    /** 测试独享的消息和缩短后的超时参数。 */
    private companion object {
        const val TEST_MESSAGE = 42_424
        const val TEST_TIMEOUT_MILLIS = 150L
    }
}
