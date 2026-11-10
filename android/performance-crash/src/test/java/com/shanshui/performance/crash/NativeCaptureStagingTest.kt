package com.shanshui.performance.crash

import com.shanshui.performance.network.CrashEvent
import com.shanshui.performance.network.CrashPayload
import com.shanshui.performance.network.CrashDevice
import com.shanshui.performance.network.CrashMemory
import com.shanshui.performance.network.CrashProcess
import com.shanshui.performance.network.NativeJavaStack
import com.shanshui.performance.network.OtherThread
import com.shanshui.performance.network.OtherThreads
import com.shanshui.performance.network.StackFrame
import com.shanshui.performance.network.ThrowableNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 验证 Native 核心原身份恢复、半写和队列幂等边界。 */
class NativeCaptureStagingTest {
    /** 所有测试只使用隔离临时目录和合成数据。 */
    @get:Rule val temporary = TemporaryFolder()

    /** 完整核心导入时沿用崩溃进程身份和发生时间，不读取当前进程现场。 */
    @Test
    fun importsCommittedCoreWithOriginalIdentity() {
        val root = temporary.newFolder("original")
        val stage = staging(root)
        val queue = FileCrashQueue(File(root, "queue"))
        val handle = requireNotNull(stage.prepare(identity("native-original"), 123))
        handle.coreFile.writeBytes(core())
        assertEquals(1, stage.recover(queue, CrashEventBudget(16 * 1024)))
        val event = queue.peekBatch(1).single().record.event
        assertEquals("old-session", event.sessionId)
        assertEquals("11111111-1111-4111-8111-111111111111", event.processId)
        assertEquals("old-build", event.buildId)
        assertEquals(1794200000123, event.occurredAt)
        assertEquals("0x0", event.crash!!.nativeCrash!!.signal.faultAddress)
        assertEquals("0xffffffffffffffff", event.crash!!.nativeCrash!!.registers.values["pc"])
        assertEquals(456, event.crash!!.nativeCrash!!.systemTid)
        assertEquals("interrupted", event.crash!!.diagnostics!!.memory!!.reason)
        assertFalse(handle.coreFile.exists())
        assertFalse(handle.identityFile.exists())
    }

    /** 已完整提交的回溯和其他 Native 线程与核心一并恢复。 */
    @Test
    fun importsCommittedTraceAlongsideOriginalCore() {
        val root = temporary.newFolder("complete-trace")
        val stage = staging(root)
        val queue = FileCrashQueue(File(root, "queue"))
        val handle = requireNotNull(stage.prepare(identity("native-trace"), 123))
        handle.coreFile.writeBytes(core())
        handle.traceFile.writeBytes(trace())
        handle.diagnosticsFile.writeBytes(diagnostics())
        assertEquals(1, stage.recover(queue, CrashEventBudget(16 * 1024)))
        val event = queue.peekBatch(1).single().record.event
        assertEquals("libexample.so", event.crash!!.nativeCrash!!.modules.single().name)
        assertEquals("0x11", event.crash!!.nativeCrash!!.backtrace.frames.single().relativePc)
        assertEquals(123, event.crash!!.diagnostics!!.nativeThreads!!.threads.single().systemTid)
        assertEquals("safe line", event.crash!!.diagnostics!!.logcat!!.main!!.lines.single())
        assertEquals(0L, event.crash!!.diagnostics!!.memory!!.rssBytes)
        assertEquals("[path]", event.crash!!.diagnostics!!.openFiles!!.entries.single().target)
        assertFalse(handle.coreFile.exists())
        assertFalse(handle.traceFile.exists())
        assertFalse(handle.diagnosticsFile.exists())
    }

    /** 安全的 abort 消息随原始信号冻结到同一事件。 */
    @Test
    fun preservesSafeAbortMessageThroughRecovery() {
        /** 本次恢复测试的隔离暂存目录。 */
        val root = temporary.newFolder("abort-recovery")
        /** 负责导入原始核心及回溯的管理器。 */
        val stage = staging(root)
        /** 保存冻结事件的隔离队列。 */
        val queue = FileCrashQueue(temporary.newFolder("abort-queue"))
        /** 绑定合成原进程的暂存文件。 */
        val handle = requireNotNull(stage.prepare(identity("abort-recovery"), 123))
        handle.coreFile.writeBytes(core(signal = 6))
        handle.traceFile.writeBytes(trace(version = 2, abortMessage = "synthetic assertion failed"))
        assertEquals(1, stage.recover(queue, CrashEventBudget(32 * 1024)))
        assertEquals("synthetic assertion failed", queue.peekBatch(1).single().record.event.crash?.nativeCrash?.signal?.abortMessage)
    }

    /** 只导入已接纳且身份匹配的 Java 补充，并与独立 Native 内存分区合并。 */
    @Test
    fun importsAcceptedJavaSupplementWithOriginalProcess() {
        val root = temporary.newFolder("java-accepted")
        val stage = staging(root)
        val queue = FileCrashQueue(File(root, "queue"))
        val handle = requireNotNull(stage.prepare(identity("native-java"), 123))
        handle.coreFile.writeBytes(core())
        handle.diagnosticsFile.writeBytes(diagnostics())
        handle.javaFile.writeText(requireNotNull(NativeJavaCodec.encode(javaRecord())))
        handle.decisionFile.writeBytes(decision(true))
        assertEquals(1, stage.recover(queue, CrashEventBudget(32 * 1024)))
        val diagnostics = queue.peekBatch(1).single().record.event.crash!!.diagnostics!!
        assertEquals("unknown", diagnostics.otherThreads!!.threads.single().relationToCrash)
        assertEquals(0L, diagnostics.memory!!.javaHeapUsedBytes)
        assertEquals(0L, diagnostics.memory!!.rssBytes)
        assertEquals(123, diagnostics.process!!.pid)
        assertEquals(35, diagnostics.device!!.apiLevel)
        assertFalse(handle.javaFile.exists())
        assertFalse(handle.decisionFile.exists())
    }

    /** 超时后即使 Java 文件迟到，恢复仍使用原缓存并忽略迟到内容。 */
    @Test
    fun rejectsLateJavaSupplementAfterDeadline() {
        val root = temporary.newFolder("java-late")
        val stage = staging(root)
        val queue = FileCrashQueue(File(root, "queue"))
        val process = CrashProcess(status = "ok", capturedAt = 10, pid = 123, name = "cached", foreground = true)
        val device = CrashDevice(status = "ok", capturedAt = 10, apiLevel = 29)
        val handle = requireNotNull(stage.prepare(identity("native-late"), 123, process, device))
        handle.coreFile.writeBytes(core())
        handle.javaFile.writeText(requireNotNull(NativeJavaCodec.encode(javaRecord())))
        handle.decisionFile.writeBytes(decision(false))
        assertEquals(1, stage.recover(queue, CrashEventBudget(32 * 1024)))
        val diagnostics = queue.peekBatch(1).single().record.event.crash!!.diagnostics!!
        assertEquals("interrupted", diagnostics.otherThreads!!.reason)
        assertEquals("cached", diagnostics.process!!.name)
        assertNull(diagnostics.process!!.foreground)
        assertEquals(29, diagnostics.device!!.apiLevel)
        assertNull(diagnostics.memory!!.javaHeapUsedBytes)
    }

    /** 半写辅助记录不能破坏已经提交的核心。 */
    @Test
    fun recoversCoreWhenTraceIsIncomplete() {
        val root = temporary.newFolder("partial-trace")
        val stage = staging(root)
        val queue = FileCrashQueue(File(root, "queue"))
        val handle = requireNotNull(stage.prepare(identity("native-partial-trace"), 123))
        handle.coreFile.writeBytes(core())
        handle.traceFile.writeBytes(trace().dropLast(1).toByteArray())
        assertEquals(1, stage.recover(queue, CrashEventBudget(16 * 1024)))
        val saved = requireNotNull(queue.peekBatch(1).single().record.event.crash?.nativeCrash)
        assertEquals("0xffffffffffffffff", saved.registers.values["pc"])
        assertEquals("interrupted", saved.backtrace.reason)
        assertTrue(saved.backtrace.frames.isEmpty())
    }

    /** 半写核心不会上传且被隔离，仍活跃进程的记录不会被提前处理。 */
    @Test
    fun quarantinesInterruptedCoreAndSkipsLiveProcess() {
        val root = temporary.newFolder("partial")
        val stage = staging(root)
        val queue = FileCrashQueue(File(root, "queue"))
        val handle = requireNotNull(stage.prepare(identity("native-partial"), 123))
        handle.coreFile.writeBytes(core().dropLast(1).toByteArray())
        val live = NativeCaptureStaging(root, isProcessAlive = { true }, logger = {})
        assertEquals(0, live.recover(queue, CrashEventBudget(16 * 1024)))
        assertTrue(handle.identityFile.exists())
        assertEquals(0, stage.recover(queue, CrashEventBudget(16 * 1024)))
        assertFalse(handle.identityFile.exists())
        assertTrue(handle.coreFile.exists())
        assertTrue(File(root, "native-partial.invalid").exists())
        assertEquals(0, stage.recover(queue, CrashEventBudget(16 * 1024)))
        assertEquals(0, queue.count())
    }

    /** 反复结束没有崩溃的进程不会使 32 份暂存额度耗尽。 */
    @Test
    fun releasesEmptyCapturesAfterRepeatedProcessTermination() {
        /** 隔离的暂存目录。 */
        val root = temporary.newFolder("empty-exits")
        /** 每次模拟启动共享的暂存管理器。 */
        val stage = staging(root)
        /** 用于接收完整事件的队列。 */
        val queue = FileCrashQueue(temporary.newFolder("empty-exits-queue"))
        repeat(40) { index ->
            /** 模拟一次已退出进程遗留的预留文件。 */
            val handle = requireNotNull(stage.prepare(identity("empty-$index"), 123))
            handle.coreFile.createNewFile()
            handle.traceFile.createNewFile()
            handle.diagnosticsFile.createNewFile()
            handle.decisionFile.createNewFile()
            assertEquals(0, stage.recover(queue, CrashEventBudget(16 * 1024)))
            assertFalse(handle.identityFile.exists())
            assertFalse(handle.coreFile.exists())
        }
        assertNotNull(stage.prepare(identity("after-empty-exits"), 123))
    }

    /** 升级后只回收身份可核实且全部数据为空的旧隔离记录。 */
    @Test
    fun reclaimsVerifiedLegacyEmptyQuarantineButPreservesCoreAndLiveOwner() {
        /** 旧隔离记录的测试目录。 */
        val root = temporary.newFolder("legacy-empty")
        /** 将原进程视为已退出的管理器。 */
        val stage = staging(root)
        /** 独立事件队列。 */
        val queue = FileCrashQueue(temporary.newFolder("legacy-empty-queue"))
        /** 能安全清理的旧空记录。 */
        val empty = requireNotNull(stage.prepare(identity("legacy-empty"), 123))
        empty.coreFile.createNewFile()
        assertTrue(empty.identityFile.renameTo(File(root, "legacy-empty.invalid")))
        /** 必须保留已写核心的旧隔离记录。 */
        val saved = requireNotNull(stage.prepare(identity("legacy-core"), 123))
        saved.coreFile.writeBytes(core())
        assertTrue(saved.identityFile.renameTo(File(root, "legacy-core.invalid")))
        /** 将原进程视为仍存活的管理器。 */
        val live = NativeCaptureStaging(root, isProcessAlive = { true }, processStartTicks = { null }, logger = {})
        live.recover(queue, CrashEventBudget(16 * 1024))
        assertTrue(File(root, "legacy-empty.invalid").exists())
        stage.recover(queue, CrashEventBudget(16 * 1024))
        assertFalse(File(root, "legacy-empty.invalid").exists())
        assertTrue(saved.coreFile.exists())
        assertTrue(File(root, "legacy-core.invalid").exists())
    }

    /** 身份文件暂时不可读时保留原文件，后续同进程重试能恢复核心。 */
    @Test
    fun retriesTransientIdentityReadFailureWithoutQuarantine() {
        /** 包含有效原核心的暂存目录。 */
        val root = temporary.newFolder("identity-io")
        /** 控制首次身份读取是否模拟 I/O 失败。 */
        var failRead = true
        /** 可注入读取失败的暂存管理器。 */
        val stage = NativeCaptureStaging(root, isProcessAlive = { false }, processStartTicks = { null },
            readIdentityText = { file -> if (failRead) throw IOException("synthetic") else file.readText() }, logger = {})
        /** 保存成功恢复事件的独立队列。 */
        val queue = FileCrashQueue(temporary.newFolder("identity-io-queue"))
        /** 需要在瞬时失败后继续保留的原核心。 */
        val handle = requireNotNull(stage.prepare(identity("identity-io"), 123))
        handle.coreFile.writeBytes(core())
        assertEquals(0, stage.recover(queue, CrashEventBudget(16 * 1024)))
        assertTrue(handle.identityFile.exists())
        assertTrue(handle.coreFile.exists())
        failRead = false
        assertEquals(1, stage.recover(queue, CrashEventBudget(16 * 1024)))
        assertEquals("identity-io", queue.peekBatch(1).single().record.event.eventId)
    }

    /** 身份内容损坏即使仍是合法 JSON，也不能覆盖原构建或会话。 */
    @Test
    fun rejectsIdentityChecksumMismatchWithoutDeletingCore() {
        val root = temporary.newFolder("identity-corrupt")
        val stage = staging(root)
        val queue = FileCrashQueue(File(root, "queue"))
        val handle = requireNotNull(stage.prepare(identity("native-identity"), 123))
        handle.coreFile.writeBytes(core())
        handle.identityFile.writeText(handle.identityFile.readText().replace("old-build", "bad-build"))
        assertEquals(0, stage.recover(queue, CrashEventBudget(16 * 1024)))
        assertEquals(0, queue.count())
        assertTrue(handle.coreFile.exists())
        assertTrue(File(root, "native-identity.invalid").exists())
    }

    /** 重启发生在队列写入后、暂存删除前时，相同内容只保留一个队列事件。 */
    @Test
    fun confirmsIdenticalQueueRecordBeforeDeletingStage() {
        val root = temporary.newFolder("idempotent")
        val stage = staging(root)
        val queue = FileCrashQueue(File(root, "queue"))
        val handle = requireNotNull(stage.prepare(identity("native-retry"), 123))
        val identityBytes = handle.identityFile.readBytes()
        val coreBytes = core()
        handle.coreFile.writeBytes(coreBytes)
        assertEquals(1, stage.recover(queue, CrashEventBudget(16 * 1024)))
        handle.identityFile.writeBytes(identityBytes)
        handle.coreFile.writeBytes(coreBytes)
        assertEquals(1, stage.recover(queue, CrashEventBudget(16 * 1024)))
        assertEquals(1, queue.count())
        assertFalse(handle.coreFile.exists())
    }

    /** 队列确认标记落盘后，即使清理只完成一半也不会重新导入或遗留配额。 */
    @Test
    fun resumesCleanupAfterCommittedCoreWasAlreadyRemoved() {
        val root = temporary.newFolder("cleanup-interrupted")
        val stage = staging(root)
        val queue = FileCrashQueue(File(root, "queue"))
        val handle = requireNotNull(stage.prepare(identity("native-cleanup"), 123))
        val identityBytes = handle.identityFile.readBytes()
        handle.coreFile.writeBytes(core())
        assertEquals(1, stage.recover(queue, CrashEventBudget(32 * 1024)))
        handle.identityFile.writeBytes(identityBytes)
        handle.traceFile.writeBytes(trace())
        handle.committedFile.writeText(handle.eventId)
        val temporaryJava = File(root, handle.javaFile.name + ".tmp-synthetic")
        temporaryJava.writeText("safe-partial")
        assertEquals(0, stage.recover(queue, CrashEventBudget(32 * 1024)))
        assertEquals(1, queue.count())
        assertFalse(handle.identityFile.exists())
        assertFalse(handle.traceFile.exists())
        assertFalse(handle.committedFile.exists())
        assertFalse(temporaryJava.exists())
    }

    /** PID 被新进程复用时按启动计数识别原进程已结束并恢复旧事件。 */
    @Test
    fun recoversWhenPidWasReusedByAnotherProcess() {
        val root = temporary.newFolder("pid-reused")
        var ticks = 10L
        val stage = NativeCaptureStaging(root, isProcessAlive = { true }, processStartTicks = { ticks }, logger = {})
        val queue = FileCrashQueue(File(root, "queue"))
        val handle = requireNotNull(stage.prepare(identity("native-pid"), 123))
        handle.coreFile.writeBytes(core())
        assertEquals(0, stage.recover(queue, CrashEventBudget(32 * 1024)))
        ticks = 20L
        assertEquals(1, stage.recover(queue, CrashEventBudget(32 * 1024)))
        assertEquals(1, queue.count())
    }

    /** 相同事件 ID 的不同队列内容不能使已提交 Native 核心被删除。 */
    @Test
    fun retainsCommittedCoreOnQueueIdentityConflict() {
        val root = temporary.newFolder("conflict")
        val stage = staging(root)
        val queue = FileCrashQueue(File(root, "queue"))
        val event = identity("native-conflict")
        val handle = requireNotNull(stage.prepare(event, 123))
        handle.coreFile.writeBytes(core())
        queue.enqueue(event.copy(crash = CrashPayload(throwableChain = listOf(ThrowableNode("E", frames = listOf(StackFrame("A", "run")))))))
        assertEquals(0, stage.recover(queue, CrashEventBudget(16 * 1024)))
        assertTrue(handle.identityFile.exists())
        assertTrue(handle.coreFile.exists())
        assertEquals(1, queue.count())
    }

    /** 达到保留数量或字节边界时不删除既有暂存以腾位置。 */
    @Test
    fun boundsStagingWithoutRemovingExistingCores() {
        val root = temporary.newFolder("quota")
        val stage = staging(root)
        val first = requireNotNull(stage.prepare(identity("native-0"), 123))
        first.coreFile.writeBytes(core())
        for (index in 1 until 32) assertNotNull(stage.prepare(identity("native-$index"), 123))
        assertNull(stage.prepare(identity("native-overflow"), 123))
        assertTrue(first.coreFile.exists())
        assertFalse(stage.discardUnused(first))
        val bytesRoot = temporary.newFolder("quota-bytes")
        File(bytesRoot, "unmanaged").outputStream().use { it.channel.truncate(0); it.write(ByteArray(16 * 1024 * 1024)) }
        assertNull(staging(bytesRoot).prepare(identity("native-large"), 123))
    }

    /** 单份暂存超过 512 KiB 时拒绝导入，并保留已提交的核心供排查。 */
    @Test
    fun rejectsOversizedRecordWithoutRemovingCommittedCore() {
        // 模拟核心提交后异常产生超限的补充记录。
        /** 本次超限测试的暂存根目录。 */
        val root = temporary.newFolder("oversized-record")
        /** 独立的 Native 暂存管理器。 */
        val stage = staging(root)
        /** 已预留的信号核心和辅助记录路径。 */
        val handle = requireNotNull(stage.prepare(identity("native-oversized"), 123))
        handle.coreFile.writeBytes(core())
        handle.traceFile.writeBytes(ByteArray(512 * 1024))
        /** 验证未错误导入的独立队列。 */
        val queue = FileCrashQueue(temporary.newFolder("oversized-queue"))
        assertEquals(0, stage.recover(queue, CrashEventBudget(32 * 1024)))
        assertTrue(handle.coreFile.exists())
        assertTrue(queue.peekBatch(10).isEmpty())
    }

    /** 创建不读取当前 Android 进程或日志的暂存管理器。 */
    private fun staging(root: File): NativeCaptureStaging = NativeCaptureStaging(root, isProcessAlive = { false }, logger = {})

    /** 构造崩溃前冻结的合成身份。 */
    private fun identity(id: String): CrashEvent = CrashEvent(schemaVersion = 2, eventId = id, eventType = "crash", occurredAt = 1,
        sessionId = "old-session", processId = "11111111-1111-4111-8111-111111111111", anonymousDeviceId = "synthetic-device",
        packageName = "com.example.app", appVersion = "1", versionCode = 1, buildId = "old-build", environment = "test",
        channel = "test", osVersion = "35", deviceModel = "synthetic")

    /** 按 JNI 写入格式构造有限核心，校验值用于验证半写边界。 */
    private fun core(signal: Int = 11): ByteArray {
        val buffer = ByteBuffer.allocate(76).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(byteArrayOf('A'.code.toByte(), 'N'.code.toByte(), 'C'.code.toByte(), 'R'.code.toByte()))
        buffer.putInt(1).putInt(buffer.capacity()).putLong(1794200000123)
        buffer.putInt(123).putInt(456).putInt(signal).putInt(1).putInt(if (signal == 6) 0 else 1).putLong(0)
        buffer.putInt(1).putInt(1)
        buffer.put(byteArrayOf('p'.code.toByte(), 'c'.code.toByte(), 0, 0, 0, 0, 0, 0)).putLong(-1L)
        var checksum = -2128831035
        for (index in 0 until buffer.position()) checksum = (checksum xor (buffer.array()[index].toInt() and 0xff)) * 16777619
        buffer.putInt(checksum)
        return buffer.array()
    }

    /** 构造包含主辅线程和一个模块的完整辅助记录。 */
    private fun trace(version: Int = 1, abortMessage: String = ""): ByteArray {
        val buffer = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(byteArrayOf(65, 78, 84, 82)).putInt(version).putInt(0).putLong(1794200000123)
        buffer.putInt(123).putInt(456).putInt(1).putInt(0).putInt(2).putInt(1)
        putText(buffer, "libexample.so")
        putText(buffer, "abcdef12")
        buffer.putInt(456).putInt(0).putInt(0).putInt(0)
        putText(buffer, "worker")
        buffer.putInt(1).putInt(0).putLong(0x11).putLong(0)
        putText(buffer, "")
        buffer.putInt(123).putInt(1).putInt(0).putInt(0)
        putText(buffer, "main")
        buffer.putInt(1).putInt(0).putLong(0x22).putLong(0)
        putText(buffer, "")
        if (version >= 2) putText(buffer, abortMessage)
        val length = buffer.position() + 4
        buffer.putInt(8, length)
        var checksum = -2128831035
        for (index in 0 until buffer.position()) checksum = (checksum xor (buffer.array()[index].toInt() and 0xff)) * 16777619
        buffer.putInt(checksum)
        return buffer.array().copyOf(length)
    }

    /** 构造独立的原进程日志、内存和 FD 分区。 */
    private fun diagnostics(): ByteArray {
        val buffer = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(byteArrayOf(65, 78, 68, 71)).putInt(1).putInt(0).putLong(1794200000123).putInt(123)
        repeat(3) { index ->
            buffer.putInt(0).putInt(0).putInt(0).putLong(1794200000123)
            buffer.putInt(if (index == 0) 1 else 0)
            if (index == 0) putText(buffer, "safe line")
        }
        buffer.putInt(1).putInt(4).putInt(0).putLong(1794200000123)
        buffer.putLong(0).putLong(4096).putLong(-1).putLong(-1)
        putText(buffer, "MemTotal: 4 kB\n")
        putText(buffer, "VmRSS: 0 kB\n")
        putText(buffer, "Max open files 1024 2048\n")
        buffer.putInt(0).putInt(0).putInt(0).putLong(1794200000123).putInt(1).putInt(1).putInt(4).putInt(5)
        val length = buffer.position() + 4
        buffer.putInt(8, length)
        var checksum = -2128831035
        for (index in 0 until buffer.position()) checksum = (checksum xor (buffer.array()[index].toInt() and 0xff)) * 16777619
        buffer.putInt(checksum)
        return buffer.array().copyOf(length)
    }

    /** 构造只包含安全合成字段的 Java 补充。 */
    private fun javaRecord(): NativeJavaDiagnostics = NativeJavaDiagnostics(1, 123, 456, 1794200000123, null,
        NativeJavaStack(status = "unavailable", reason = "interrupted"),
        OtherThreads(status = "ok", observedCount = 1, omittedCount = 0, threads = listOf(
            OtherThread(javaId = "99", name = "worker", state = "RUNNABLE", priority = 5, daemon = true,
                mainThread = false, relationToCrash = "unknown"))),
        CrashMemory(status = "ok", javaHeapUsedBytes = 0, javaHeapMaxBytes = 1024),
        CrashProcess(status = "ok", pid = 123, name = "original"), CrashDevice(status = "ok", apiLevel = 35))

    /** 按信号处理器格式构造带校验的补充接纳决定。 */
    private fun decision(accepted: Boolean): ByteArray {
        val buffer = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(byteArrayOf(65, 78, 68, 67)).putInt(1).putLong(1794200000123)
            .putInt(123).putInt(456).putInt(if (accepted) 1 else 0).putInt(36)
        var checksum = -2128831035
        for (index in 0 until buffer.position()) checksum = (checksum xor (buffer.array()[index].toInt() and 0xff)) * 16777619
        buffer.putInt(checksum)
        return buffer.array()
    }

    /** 写入测试记录中的有限安全文本。 */
    private fun putText(buffer: ByteBuffer, value: String) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        buffer.putInt(bytes.size).put(bytes)
    }
}
