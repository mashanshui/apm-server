package com.shanshui.performance.crash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.shanshui.performance.network.CrashBatchRequest
import com.shanshui.performance.network.CrashEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/** 以隔离进程验证实际信号、安装路径、线程回溯和有界完成。 */
@RunWith(AndroidJUnit4::class)
class NativeDumperSignalTest {
    /** 只读取独立测试 APK 自己的私有目录。 */
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** 每次测试后撤销测试进程的信号处理权。 */
    @After
    fun cleanup() { NativeCrashBridge.uninstall(); NativeSignalProbe.nativeConfigureSignal(11, null) }

    /** 主线程的同步故障必须保留零地址和可取得的原始栈。 */
    @Test
    fun capturesMainThreadSignalAndFrames() {
        val result = capture("main")
        assertEquals(result.first.pid, result.first.systemTid)
        assertEquals("0x0", result.first.faultAddress)
        assertTrue(result.second.backtrace.frames.isNotEmpty())
        assertTrue(result.second.modules.isNotEmpty())
    }

    /** 纯 Native 线程与进程主线程按系统 TID 区分。 */
    @Test
    fun capturesNativeThread() {
        val result = capture("native")
        assertTrue(result.first.pid != result.first.systemTid)
        assertTrue(result.second.backtrace.frames.isNotEmpty())
        assertTrue(result.second.nativeThreads.threads.none { it.systemTid == result.first.systemTid })
    }

    /** 纯 Native 线程 SIGABRT 可安全取得完整消息时保留原线程与消息。 */
    @Test
    fun capturesSafeAbortMessageFromNativeThread() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        /** 从独立纯 Native 线程得到的完整回溯。 */
        val trace = captureAbort("synthetic assertion failed")
        assertEquals("synthetic assertion failed", trace.abortMessage)
    }

    /** 不安全的 abort 消息在首次落盘前省略，仍保留原信号和回溯。 */
    @Test
    fun omitsSensitiveAbortMessageBeforePersistence() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        /** 包含合成凭据的消息不应写入记录。 */
        val trace = captureAbort("X-App-Key: apm_ak_AbCdefGHijkLmnoPQRstuVwXYZ")
        assertEquals(null, trace.abortMessage)
    }

    /** 触发隔离纯 Native 线程的 SIGABRT 并读取已提交的有限记录。 */
    private fun captureAbort(message: String): NativeTraceRecord {
        /** 此次测试专用的临时文件前缀。 */
        val id = "native-abort-${UUID.randomUUID()}"
        /** 信号入口提交的合成核心。 */
        val coreFile = File(context.cacheDir, "$id.core")
        /** dumper 提交的有限回溯。 */
        val traceFile = File(context.cacheDir, "$id.trace")
        /** dumper 提交的辅助诊断。 */
        val diagnosticsFile = File(traceFile.absolutePath + ".diagnostics")
        /** 测试 APK 安装目录中的 dumper。 */
        val dumper = File(context.applicationInfo.nativeLibraryDir, "libapm_crash_dumper.so")
        NativeSignalProbe.nativeConfigureSignal(6, message)
        assertTrue(NativeCrashBridge.install(coreFile))
        assertEquals(6, NativeSignalProbe.nativeForkDumperCrash(dumper.absolutePath, traceFile.absolutePath, true, false))
        /** 原崩溃信号核心和与之绑定的回溯。 */
        val core = requireNotNull(NativeCoreCodec.decode(coreFile.readBytes()))
        /** 回溯中仅允许出现安全的消息。 */
        val trace = requireNotNull(NativeTraceCodec.decode(traceFile.readBytes(), core))
        assertTrue(core.pid != core.systemTid)
        assertTrue(trace.backtrace.frames.isNotEmpty())
        if (message.contains("apm_ak_")) assertFalse(traceFile.readBytes().toString(Charsets.ISO_8859_1).contains("apm_ak_"))
        assertNoRawDumps(coreFile, traceFile, diagnosticsFile)
        coreFile.delete(); traceFile.delete(); diagnosticsFile.delete()
        return trace
    }

    /** 父进程存在 Java 持有的 FD 时，辅助子进程关闭自己的副本不能触发 fdsan 或破坏父进程。 */
    @Test
    fun capturesWhileJavaOwnsAnOpenFileDescriptor() {
        val file = File(context.cacheDir, "native-owned-fd-${UUID.randomUUID()}")
        RandomAccessFile(file, "rw").use { owned ->
            owned.writeUTF("synthetic")
            assertTrue(capture("main").second.backtrace.frames.isNotEmpty())
            owned.seek(0)
            assertEquals("synthetic", owned.readUTF())
        }
        file.delete()
    }

    /** 即使崩溃线程已被暂停，看护者也应在五秒左右终止卡住的子进程。 */
    @Test
    fun killsHungDumperAndKeepsCommittedCore() {
        val id = "native-timeout-${UUID.randomUUID()}"
        val coreFile = File(context.cacheDir, "$id.core")
        val traceFile = File(context.cacheDir, "$id.trace")
        val diagnosticsFile = File(traceFile.absolutePath + ".diagnostics")
        val dumper = File(context.applicationInfo.nativeLibraryDir, "libapm_crash_dumper.so")
        assertTrue(NativeCrashBridge.install(coreFile))
        val started = System.nanoTime()
        assertEquals(11, NativeSignalProbe.nativeForkDumperCrash(dumper.absolutePath, traceFile.absolutePath, false, true))
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertTrue("没有进入有界等待：$elapsed", elapsed >= 4000)
        assertTrue("超时未及时回收：$elapsed", elapsed < 12_000)
        assertNotNull(NativeCoreCodec.decode(coreFile.readBytes()))
        assertEquals(0L, traceFile.length())
        assertNoRawDumps(coreFile, traceFile, diagnosticsFile)
        coreFile.delete()
        traceFile.delete()
        diagnosticsFile.delete()
    }

    /** 独立程序启动失败时仍保留已提交核心，不伪造回溯。 */
    @Test
    fun keepsCommittedCoreWhenDumperCannotExecute() {
        val id = "native-exec-failed-${UUID.randomUUID()}"
        val coreFile = File(context.cacheDir, "$id.core")
        val traceFile = File(context.cacheDir, "$id.trace")
        val diagnosticsFile = File(traceFile.absolutePath + ".diagnostics")
        val missing = File(context.applicationInfo.nativeLibraryDir, "libapm_missing_for_test.so")
        assertTrue(NativeCrashBridge.install(coreFile))
        assertEquals(11, NativeSignalProbe.nativeForkDumperCrash(missing.absolutePath, traceFile.absolutePath, false, false))
        assertNotNull(NativeCoreCodec.decode(coreFile.readBytes()))
        assertEquals(0L, traceFile.length())
        assertNoRawDumps(coreFile, traceFile, diagnosticsFile)
        coreFile.delete()
        traceFile.delete()
        diagnosticsFile.delete()
    }

    /** Java 补充迟到时两秒后固定拒绝决定，不会等待迟到的成功结果。 */
    @Test
    fun rejectsCallbackThatMissesItsDeadline() {
        val id = "native-callback-timeout-${UUID.randomUUID()}"
        val coreFile = File(context.cacheDir, "$id.core")
        val traceFile = File(context.cacheDir, "$id.trace")
        val diagnosticsFile = File(traceFile.absolutePath + ".diagnostics")
        val decisionFile = File(traceFile.absolutePath + ".decision")
        val dumper = File(context.applicationInfo.nativeLibraryDir, "libapm_crash_dumper.so")
        assertTrue(NativeCrashBridge.install(coreFile))
        val started = System.nanoTime()
        assertEquals(11, NativeSignalProbe.nativeForkDumperCrash(dumper.absolutePath, traceFile.absolutePath, false, false, true))
        val elapsed = (System.nanoTime() - started) / 1_000_000
        val core = requireNotNull(NativeCoreCodec.decode(coreFile.readBytes()))
        assertEquals(false, NativeJavaDecision.accepts(decisionFile, core))
        assertTrue("没有等待回调截止：$elapsed", elapsed >= 1800)
        assertTrue("等待回调超过边界：$elapsed", elapsed < 6000)
        assertNoRawDumps(coreFile, traceFile, diagnosticsFile, decisionFile)
        coreFile.delete()
        traceFile.delete()
        diagnosticsFile.delete()
        decisionFile.delete()
    }

    /** 等待有限核心和有效辅助记录，不把半写误判为成功。 */
    private fun capture(mode: String): Pair<NativeCoreRecord, NativeTraceRecord> {
        val id = "native-dumper-${UUID.randomUUID()}"
        val coreFile = File(context.cacheDir, "$id.core")
        val traceFile = File(context.cacheDir, "$id.trace")
        val diagnosticsFile = File(traceFile.absolutePath + ".diagnostics")
        val dumper = File(context.applicationInfo.nativeLibraryDir, "libapm_crash_dumper.so")
        val started = System.nanoTime()
        assertTrue("测试核心初始化失败", NativeCrashBridge.install(coreFile))
        assertEquals("隔离子进程必须因真实 SIGSEGV 结束", 11,
            NativeSignalProbe.nativeForkDumperCrash(dumper.absolutePath, traceFile.absolutePath, mode == "native", false))
        // 隔离进程已经退出，看护者已回收 dumper；此时不再有合法迟到写入。
        val core = runCatching { NativeCoreCodec.decode(coreFile.readBytes()) }.getOrNull()
        val trace = core?.let { value -> runCatching { NativeTraceCodec.decode(traceFile.readBytes(), value) }.getOrNull() }
        assertNotNull("未取得完整 Native 核心", core)
        assertNotNull("未取得完整 Native 回溯记录", trace)
        val diagnosticsBytes = diagnosticsFile.readBytes()
        val diagnostics = NativeDiagnosticsCodec.decode(diagnosticsBytes, requireNotNull(core))
        assertNotNull("未取得完整原进程诊断", diagnostics)
        val mainLog = requireNotNull(diagnostics).logcat.main!!
        if (mainLog.status == "unavailable" && mainLog.reason == "permission_denied") {
            // 部分旧系统的 SELinux 不允许应用执行 logcat，须保留真实权限失败。
            assertTrue(mainLog.lines.isEmpty())
        } else {
            assertTrue("没有保留可读取的普通日志：status=${mainLog.status} reason=${mainLog.reason} count=${mainLog.lines.size}",
                mainLog.lines.any { it.contains("safe-native-log") })
        }
        assertNotNull("未读取原进程 RSS", diagnostics.memory.rssBytes)
        assertNotNull("未读取原进程 VSS", diagnostics.memory.vssBytes)
        assertTrue("未读取原进程 FD", (diagnostics.openFiles.count ?: 0) > 0)
        val diagnosticText = diagnosticsBytes.toString(Charsets.ISO_8859_1)
        assertFalse(diagnosticText.contains("synthetic-secret"))
        assertFalse(diagnosticText.contains("test@example.test"))
        assertFalse(diagnosticText.contains("138-0013-8000"))
        assertFalse(diagnosticText.contains("/data/user/0/private"))
        assertFalse(diagnosticText.contains("1234abcd1234abcd"))
        assertFalse(diagnosticText.contains("apm_ak_AbCdefGHijkLmnoPQRstuVwXYZ"))
        assertFalse(diagnosticText.contains("0000007fe0ef1ffa"))
        assertTrue("独立回溯超过测试时限", (System.nanoTime() - started) / 1_000_000 < 15_000)
        assertNoRawDumps(coreFile, traceFile, diagnosticsFile)
        verifySanitizedQueueAndRequest(requireNotNull(core), coreFile, traceFile, diagnosticsFile)
        coreFile.delete()
        traceFile.delete()
        diagnosticsFile.delete()
        return requireNotNull(core) to requireNotNull(trace)
    }

    /** 将设备实际记录导入队列，检查固定敏感样本未进入事件、请求和排障日志。 */
    private fun verifySanitizedQueueAndRequest(core: NativeCoreRecord, coreFile: File, traceFile: File, diagnosticsFile: File) {
        // 只复制本次隔离子进程产生的脱敏记录，使用临时身份和私有队列。
        /** 本次测试的私有临时目录。 */
        val root = File(context.cacheDir, "native-privacy-${UUID.randomUUID()}")
        /** 收集安全排障日志以核查敏感样本。 */
        val logs = mutableListOf<String>()
        /** 导入真实辅助记录的暂存管理器。 */
        val stage = NativeCaptureStaging(File(root, "stage"), isProcessAlive = { false }, logger = { logs.add(it) })
        /** 本次测试使用的独立上传队列。 */
        val queue = FileCrashQueue(File(root, "queue"))
        /** 不含业务数据的模拟事件身份。 */
        val event = CrashEvent(schemaVersion = 2, eventId = UUID.randomUUID().toString(), eventType = "crash", occurredAt = 1,
            sessionId = "synthetic-session", processId = "11111111-1111-4111-8111-111111111111",
            anonymousDeviceId = "synthetic-device", packageName = "com.example.app", appVersion = "1", versionCode = 1,
            buildId = "synthetic-build", environment = "test", channel = "test", osVersion = "35", deviceModel = "synthetic")
        /** 已绑定原崩溃进程的暂存路径。 */
        val handle = requireNotNull(stage.prepare(event, core.pid))
        coreFile.copyTo(handle.coreFile, overwrite = true)
        traceFile.copyTo(handle.traceFile, overwrite = true)
        diagnosticsFile.copyTo(handle.diagnosticsFile, overwrite = true)
        assertEquals(1, stage.recover(queue, CrashEventBudget(240 * 1024)))
        /** 队列中已经冻结的 Native 事件。 */
        val frozen = queue.peekBatch(1).single().record.event
        /** 实际上传协议格式的 JSON 请求。 */
        val request = Gson().toJson(CrashBatchRequest("synthetic-request", listOf(frozen)))
        /** 读取隔离队列文件以检查首次落盘结果。 */
        val persisted = File(root, "queue").walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        for (secret in listOf("synthetic-secret", "test@example.test", "138-0013-8000", "/data/user/0/private", "1234abcd1234abcd",
            "apm_ak_AbCdefGHijkLmnoPQRstuVwXYZ", "0000007fe0ef1ffa")) {
            assertFalse(request.contains(secret))
            assertFalse(persisted.contains(secret))
            assertFalse(logs.any { it.contains(secret) })
        }
        root.deleteRecursively()
    }

    /** 对正常、启动失败和超时路径的全部持久记录检查禁止转储。 */
    private fun assertNoRawDumps(vararg files: File) {
        for (file in files.filter { it.isFile }) {
            val stored = file.readBytes().toString(Charsets.ISO_8859_1).lowercase()
            assertFalse(stored.contains("memory near"))
            assertFalse(stored.contains("memory map"))
            assertFalse(stored.contains("stack:"))
            assertFalse(stored.contains("1234abcd1234abcd"))
        }
    }
}
