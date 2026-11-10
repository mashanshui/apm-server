package com.shanshui.performance.crash

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.shanshui.performance.core.AtomicFileWriter
import com.shanshui.performance.network.CrashDevice
import com.shanshui.performance.network.CrashMemory
import com.shanshui.performance.network.CrashProcess
import com.shanshui.performance.network.CrashedThread
import com.shanshui.performance.network.NativeJavaStack
import com.shanshui.performance.network.OtherThreads
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** 与已提交 Native 核心绑定的原进程 Java 辅助快照。 */
internal data class NativeJavaDiagnostics(
    /** 快照格式版本。 */ val version: Int,
    /** 原进程 PID。 */ val pid: Int,
    /** 崩溃线程 TID。 */ val tid: Int,
    /** 信号发生时间。 */ val occurredAt: Long,
    /** 可可靠关联时的 Java 线程。 */ val crashedThread: CrashedThread?,
    /** 可可靠关联时的 Java 栈。 */ val javaStack: NativeJavaStack,
    /** 其他或关联未知的 Java 线程。 */ val otherThreads: OtherThreads,
    /** Java 和 Native 堆数值。 */ val memory: CrashMemory,
    /** 原进程环境。 */ val process: CrashProcess,
    /** 原设备环境。 */ val device: CrashDevice,
)

/** 内容原文和摘要组成的完整性信封。 */
private data class NativeJavaEnvelope(
    /** 格式版本。 */ val version: Int,
    /** 已脱敏快照的 JSON。 */ val content: String,
    /** 原 JSON 字节的 SHA-256。 */ val sha256: String,
)

/** 在普通工作者线程采集并原子写入已脱敏、有限的 Java 分区。 */
internal class NativeJavaDiagnosticsCapture(
    /** 独立的 Java 线程采集器。 */ private val threads: ThreadDiagnosticsCollector,
    /** 原进程运行时和设备信息采集器。 */ private val environment: EnvironmentDiagnosticsCollector,
    /** 只使用信号发生前预定的本地文件。 */ private val target: File,
    /** 单调时间用于检查回调截止。 */ private val nanoTime: () -> Long = { System.nanoTime() },
) {
    /** 捕获失败或超过截止时不宣称成功，信号处理器会拒绝迟到记录。 */
    fun capture(deadlineNanos: Long, pid: Int, tid: Int, occurredAt: Long): Boolean {
        return try {
            if (pid <= 0 || tid <= 0 || occurredAt <= 0 || nanoTime() >= deadlineNanos) return false
            val threadResult = threads.collectNative(pid, tid, deadlineNanos)
            val environmentResult = environment.collectNativeJava(deadlineNanos)
            if (nanoTime() >= deadlineNanos) return false
            val record = NativeJavaDiagnostics(1, pid, tid, occurredAt, threadResult.crashedThread, threadResult.javaStack,
                threadResult.otherThreads, environmentResult.memory, environmentResult.process, environmentResult.device)
            val encoded = NativeJavaCodec.encode(record) ?: return false
            if (nanoTime() >= deadlineNanos) return false
            AtomicFileWriter.write(target, encoded)
            nanoTime() < deadlineNanos
        } catch (_: Throwable) {
            false
        }
    }
}

/** Java 暂存只接受完整校验且与原核心一致的记录。 */
internal object NativeJavaCodec {
    /** 与事件序列化使用相同转义策略。 */ private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    /** 单份快照的实际 UTF-8 信封上限。 */ const val MAX_BYTES = 30 * 1024

    /** 优先保留可关联主栈，裁剪其他线程直到信封满足上限。 */
    fun encode(value: NativeJavaDiagnostics): String? {
        var current = value
        repeat(160) {
            val content = gson.toJson(current)
            val encoded = gson.toJson(NativeJavaEnvelope(1, content, digest(content)))
            if (encoded.toByteArray(StandardCharsets.UTF_8).size <= MAX_BYTES) return encoded
            val group = current.otherThreads
            if (group.threads.isNotEmpty()) {
                val retained = group.threads.dropLast(1)
                current = current.copy(otherThreads = group.copy(status = "partial", reason = "size_limit", truncated = true,
                    omittedCount = group.observedCount?.let { count -> (count - retained.size).coerceAtLeast(0) }, threads = retained))
            } else if (current.javaStack.frames.isNotEmpty()) {
                current = current.copy(javaStack = current.javaStack.copy(status = "partial", reason = "size_limit", truncated = true,
                    frames = current.javaStack.frames.dropLast(1)))
            } else return null
        }
        return null
    }

    /** 校验长度、摘要以及核心身份；坏记录不会覆盖原始信号。 */
    fun decode(file: File, core: NativeCoreRecord): NativeJavaDiagnostics? = runCatching {
        require(file.isFile && file.length() in 1..MAX_BYTES.toLong())
        val envelope = requireNotNull(gson.fromJson(file.readText(StandardCharsets.UTF_8), NativeJavaEnvelope::class.java))
        require(envelope.version == 1 && envelope.sha256.matches(Regex("[a-f0-9]{64}")) &&
            MessageDigest.isEqual(envelope.sha256.toByteArray(StandardCharsets.US_ASCII),
                digest(envelope.content).toByteArray(StandardCharsets.US_ASCII)))
        val value = requireNotNull(gson.fromJson(envelope.content, NativeJavaDiagnostics::class.java))
        require(value.version == 1 && value.pid == core.pid && value.tid == core.systemTid && value.occurredAt == core.occurredAt)
        require(value.otherThreads.threads.size <= 64 && value.otherThreads.threads.all { it.frames.size <= 32 })
        require(value.javaStack.frames.size <= 32 && value.process.pid == core.pid)
        value
    }.getOrNull()

    /** 对原始内容计算稳定摘要，避免半写记录被识别成完整快照。 */
    private fun digest(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** 信号处理器在等待结束时写入的接纳决定，拒绝迟到的 Java 文件。 */
internal object NativeJavaDecision {
    /** 未写决定表示进程在决定前退出；进程已死亡后完整快照不会再变化。 */
    fun accepts(file: File, core: NativeCoreRecord): Boolean? = runCatching {
        if (!file.isFile || file.length() == 0L) return null
        val bytes = file.readBytes()
        require(bytes.size == 36 && bytes.sliceArray(0..3).contentEquals(byteArrayOf(65, 78, 68, 67)))
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.getInt(4) == 1 && buffer.getLong(8) == core.occurredAt && buffer.getInt(16) == core.pid &&
            buffer.getInt(20) == core.systemTid && buffer.getInt(24) in 0..1 && buffer.getInt(28) == 36)
        var checksum = -2128831035
        for (index in 0 until 32) checksum = (checksum xor (bytes[index].toInt() and 0xff)) * 16777619
        require(buffer.getInt(32) == checksum)
        buffer.getInt(24) == 1
    }.getOrElse { false }
}
