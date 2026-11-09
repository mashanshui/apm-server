package com.shanshui.performance.crash

import android.os.Looper
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.shanshui.performance.network.CrashedThread
import com.shanshui.performance.network.OtherThread
import com.shanshui.performance.network.OtherThreads
import com.shanshui.performance.network.StackFrame
import java.nio.charset.StandardCharsets

/** 分别携带崩溃线程和其他线程的采集结果。 */
internal data class ThreadDiagnostics(
    /** 处理器收到的线程及系统 TID。 */ val crashedThread: CrashedThread,
    /** 其他线程的有界近似快照。 */ val otherThreads: OtherThreads,
)

/** 通过公开 JVM 接口采集线程，不将 Java 线程 ID 冒充系统 TID。 */
internal class ThreadDiagnosticsCollector(
    /** 用于识别应用自身的堆栈帧。 */
    private val applicationPackage: String,
    /** 可注入的 JVM 全线程快照来源。 */
    private val allThreads: () -> Map<Thread, Array<StackTraceElement>> = { Thread.getAllStackTraces() },
    /** 可注入的 Android 主线程来源。 */
    private val mainThread: () -> Thread? = { Looper.getMainLooper()?.thread },
    /** 原采集时间来源。 */
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** 等待预算使用单调时间。 */
    private val nanoTime: () -> Long = { System.nanoTime() },
    /** 与上传器一致的 JSON 编码。 */
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create(),
) {
    /** 在预算内采集线程；虚拟机全线程调用本身可能无法取消。 */
    fun collect(crashed: Thread, systemTid: Int?, deadlineNanos: Long, skipExpensive: Boolean): ThreadDiagnostics {
        val capturedAt = clock()
        val main = runCatching { mainThread() }.getOrNull()
        val crashedInfo = crashedInfo(crashed, systemTid, capturedAt, main)
        if (skipExpensive) {
            return ThreadDiagnostics(crashedInfo, OtherThreads(status = "skipped", reason = "memory_pressure", capturedAt = capturedAt))
        }
        if (nanoTime() >= deadlineNanos) {
            return ThreadDiagnostics(crashedInfo, OtherThreads(status = "skipped", reason = "timeout", capturedAt = capturedAt))
        }
        return ThreadDiagnostics(crashedInfo, captureOtherThreads(crashed, main, capturedAt, deadlineNanos))
    }

    /** 在处理器线程取得崩溃线程基本身份，辅助采集超时也能保留此信息。 */
    fun captureCrashed(crashed: Thread, systemTid: Int?): CrashedThread =
        crashedInfo(crashed, systemTid, clock(), runCatching { mainThread() }.getOrNull())

    /** 将崩溃线程属性映射为独立分区。 */
    private fun crashedInfo(crashed: Thread, systemTid: Int?, capturedAt: Long, main: Thread?): CrashedThread = try {
            CrashedThread(
                status = "ok", capturedAt = capturedAt, javaId = crashed.id.toString(),
                name = CrashSanitizer.sanitize(crashed.name, MAX_TEXT), state = crashed.state.name,
                priority = crashed.priority, daemon = crashed.isDaemon,
                mainThread = main?.let { it === crashed }, systemTid = systemTid?.takeIf { it > 0 },
            )
        } catch (_: Throwable) {
            CrashedThread(status = "unavailable", reason = "io_error", capturedAt = capturedAt)
        }

    /** 对象身份排除崩溃线程，优先采集可获得的主线程。 */
    private fun captureOtherThreads(crashed: Thread, main: Thread?, capturedAt: Long, deadlineNanos: Long): OtherThreads {
        val snapshot = try {
            allThreads()
        } catch (_: SecurityException) {
            return OtherThreads(status = "unavailable", reason = "permission_denied", capturedAt = capturedAt)
        } catch (_: OutOfMemoryError) {
            return OtherThreads(status = "unavailable", reason = "memory_pressure", capturedAt = capturedAt)
        } catch (_: Throwable) {
            return OtherThreads(status = "unavailable", reason = "io_error", capturedAt = capturedAt)
        }
        val observed = snapshot.keys.count { it !== crashed }
        val ordered = sequence {
            // 先取主线程，再按快照迭代顺序保存其余线程。
            if (main != null && main !== crashed && snapshot.containsKey(main)) yield(main to requireNotNull(snapshot[main]))
            for ((thread, frames) in snapshot) {
                if (thread !== crashed && thread !== main) yield(thread to frames)
            }
        }
        val retained = mutableListOf<OtherThread>()
        var reason: String? = null
        for ((thread, frames) in ordered) {
            if (nanoTime() >= deadlineNanos) { reason = "timeout"; break }
            if (retained.size >= MAX_THREADS) { reason = reason ?: "count_limit"; break }
            val mapped = frames.take(MAX_FRAMES).map(::frame)
            var current = try {
                OtherThread(
                    javaId = thread.id.toString(), name = CrashSanitizer.sanitize(thread.name, MAX_TEXT),
                    state = thread.state.name, priority = thread.priority, daemon = thread.isDaemon,
                    mainThread = thread === main, truncated = frames.size > MAX_FRAMES, frames = mapped,
                )
            } catch (_: Throwable) {
                reason = reason ?: "io_error"
                continue
            }
            if (current.truncated) reason = reason ?: "count_limit"
            // 每增加一条之前检查实际 JSON 字节，必要时减少本线程帧。
            while (encodedSize(retained + current, observed, capturedAt) > MAX_THREAD_BYTES && current.frames.isNotEmpty()) {
                current = current.copy(frames = current.frames.dropLast(1), truncated = true)
                reason = reason ?: "size_limit"
            }
            if (encodedSize(retained + current, observed, capturedAt) > MAX_THREAD_BYTES) {
                reason = reason ?: "size_limit"
                break
            }
            retained += current
        }
        val omitted = (observed - retained.size).coerceAtLeast(0)
        if (omitted > 0) reason = reason ?: "count_limit"
        val status = when {
            reason == null -> "ok"
            retained.isEmpty() -> "unavailable"
            else -> "partial"
        }
        return OtherThreads(status = status, reason = reason, truncated = reason != null, capturedAt = capturedAt,
            observedCount = observed, omittedCount = omitted, threads = retained)
    }

    /** 将 JVM 栈元素映射为安全有界的结构化 Java 帧。 */
    private fun frame(value: StackTraceElement): StackFrame {
        val className = CrashSanitizer.sanitize(value.className.ifBlank { "<unknown>" }, MAX_TEXT)
        return StackFrame(
            className = className,
            methodName = CrashSanitizer.sanitize(value.methodName.ifBlank { "<unknown>" }, MAX_TEXT),
            fileName = value.fileName?.let { CrashSanitizer.sanitize(it, MAX_TEXT) },
            lineNumber = value.lineNumber.takeIf { it in 0..MAX_LINE_NUMBER } ?: -1,
            applicationFrame = className == applicationPackage || className.startsWith("$applicationPackage."),
        )
    }

    /** 将状态与编码开销一并计入线程分区预算。 */
    private fun encodedSize(threads: List<OtherThread>, observed: Int, capturedAt: Long): Int {
        val value = OtherThreads(status = "partial", reason = "size_limit", truncated = true, capturedAt = capturedAt,
            observedCount = observed, omittedCount = (observed - threads.size).coerceAtLeast(0), threads = threads)
        return gson.toJson(value).toByteArray(StandardCharsets.UTF_8).size
    }

    /** 线程和文本数量上限均为编码前的第一层资源边界。 */
    private companion object {
        const val MAX_THREADS = 64
        const val MAX_FRAMES = 32
        const val MAX_TEXT = 512
        const val MAX_THREAD_BYTES = 48 * 1024
        const val MAX_LINE_NUMBER = 1_000_000_000
    }
}
