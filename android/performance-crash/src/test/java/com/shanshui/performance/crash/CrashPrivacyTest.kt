package com.shanshui.performance.crash

import com.shanshui.performance.network.CrashEvent
import com.shanshui.performance.network.CrashPayload
import com.shanshui.performance.network.StackFrame
import com.shanshui.performance.network.ThrowableNode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 验证真实采集组合在本地持久化之前清理合成敏感文本。 */
class CrashPrivacyTest {
    /** 本地文件只写入测试临时目录。 */
    @get:Rule val temporaryFolder = TemporaryFolder()

    /** 线程名、日志、proc 与 FD 均不泄露哨兵，结构化身份和零值原样保留。 */
    @Test
    fun sanitizesBeforeDiskAndKeepsStructuredFields() {
        val root = temporaryFolder.newFolder("privacy")
        val crashed = Thread({}, "token=secret-token")
        val other = Thread({}, "alice@example.com")
        val frames = arrayOf(StackTraceElement("com.example.A", "run", "/data/user/0/com.example/files/private-file", 1))
        val threads = ThreadDiagnosticsCollector("com.example", allThreads = { mapOf(crashed to frames, other to frames) },
            mainThread = { other })
        val environment = EnvironmentDiagnosticsCollector(SyntheticSource(), CachedCrashEnvironment(
            pid = 123, processName = "com.example.app", sdkInitializedAt = 1, processStartedAt = null,
            apiLevel = 35, abis = listOf("arm64-v8a"), manufacturer = "synthetic", brand = "synthetic", buildFingerprint = "test"),
            foreground = { true })
        val logcat = LogcatDiagnosticsCollector(starter = {
            SyntheticProcess("11-08 10:00:00.000 123 456 I Test: $sensitive\n")
        })
        val collector = CrashDiagnosticsCollector(threads, environment, logcat, 123, 35)
        val worker = Executors.newSingleThreadExecutor()
        val logs = mutableListOf<String>()
        val queue = FileCrashQueue(root)
        val event = CrashEvent(schemaVersion = 2, eventId = "privacy-event", eventType = "crash", occurredAt = 1,
            sessionId = "session", processId = "11111111-1111-4111-8111-111111111111", anonymousDeviceId = "device",
            packageName = "com.example.app", appVersion = "1", versionCode = 1, buildId = "build-13800138000",
            environment = "test", channel = "test", osVersion = "35", deviceModel = "synthetic",
            crash = CrashPayload(throwableChain = listOf(ThrowableNode("E", frames = listOf(StackFrame("A", "run"))))))
        try {
            val coordinator = CrashCaptureCoordinator(queue, CrashEventBudget(256 * 1024), collector, worker, logger = logs::add)
            assertTrue(coordinator.capture(event, crashed, 456, IllegalStateException()))
            val saved = queue.peekBatch(1).single().record.event
            val disk = root.resolve("events/privacy-event.json").readText()
            for (secret in listOf("secret-token", "alice@example.com", "13800138001", "secret.example", "private-file")) {
                assertFalse("本地记录含 $secret", disk.contains(secret))
                assertFalse("排障日志含 $secret", logs.any { it.contains(secret) })
            }
            assertEquals("11111111-1111-4111-8111-111111111111", saved.processId)
            assertEquals("build-13800138000", saved.buildId)
            assertEquals(456, saved.crash!!.diagnostics!!.crashedThread!!.systemTid)
            assertEquals(123, saved.crash!!.diagnostics!!.process!!.pid)
            assertEquals(0L, saved.crash!!.diagnostics!!.memory!!.javaHeapUsedBytes)
            assertTrue(logs.any { it.contains("frozen") })
        } finally {
            worker.shutdownNow()
            logcat.close()
        }
    }

    /** 固定的合成秘密只用于断言脱敏，不对应真实设备或账户。 */
    private val sensitive = "token=secret-token alice@example.com 13800138001 https://secret.example/path /data/user/0/com.example/files/private-file"

    /** 返回含敏感哨兵的受控 proc 文本与文件目标。 */
    private inner class SyntheticSource : CrashEnvironmentSource {
        /** 提供合法零值。 */ override fun runtimeHeap() = RuntimeHeapSnapshot(0, 1024)
        /** 提供合成 PSS。 */ override fun debugMemory() = DebugMemorySnapshot(1, 2, 3, 4)
        /** 返回受控 proc 文本。 */ override fun readProc(path: String, maxBytes: Int) = BoundedProcText(sensitive, false)
        /** 返回一个合成 FD。 */ override fun fileDescriptors() = listOf(3)
        /** 返回不能保留文件名的私有目标。 */ override fun fileTarget(fd: Int) = "/data/user/0/com.example/files/private-file"
    }

    /** 合并输出的受控进程，不调用 Android 系统命令。 */
    private class SyntheticProcess(text: String) : Process() {
        /** 标准输出和错误流。 */ private val input = ByteArrayInputStream(text.toByteArray())
        /** 没有独立错误内容。 */ private val error = ByteArrayInputStream(byteArrayOf())
        /** 无需写入的输入流。 */ private val output = ByteArrayOutputStream()
        /** 返回可读取流。 */ override fun getInputStream(): InputStream = input
        /** 返回空错误流。 */ override fun getErrorStream(): InputStream = error
        /** 返回无用输出流。 */ override fun getOutputStream(): OutputStream = output
        /** 测试进程成功退出。 */ override fun waitFor(): Int = 0
        /** 返回成功状态。 */ override fun exitValue(): Int = 0
        /** 关闭输出。 */ override fun destroy() { input.close() }
    }
}
