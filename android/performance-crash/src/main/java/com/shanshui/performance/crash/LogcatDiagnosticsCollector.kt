package com.shanshui.performance.crash

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.shanshui.performance.network.CrashLogcat
import com.shanshui.performance.network.LogcatBuffer
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 启动固定参数的 logcat，并合并错误流以避免子进程阻塞。 */
internal fun startLogcatProcess(command: List<String>): Process =
    ProcessBuilder(command).redirectErrorStream(true).start()

/** 仅通过固定参数读取本进程日志，超时任务负责销毁子进程和关闭流。 */
internal class LogcatDiagnosticsCollector(
    /** 启动器必须合并标准输出和标准错误，避免错误流填满导致死锁。 */
    private val starter: (List<String>) -> Process = ::startLogcatProcess,
    /** 唯一的超时清理线程，不为每个缓冲区创建等待线程。 */
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "crash-logcat-timeout").apply { isDaemon = true }
    },
    /** 原采集时间来源。 */
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** 等待预算使用单调时间。 */
    private val nanoTime: () -> Long = { System.nanoTime() },
    /** 与上传器一致的 JSON 编码。 */
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create(),
) : Closeable {
    /** 当前运行的单个日志子进程。 */
    private val active = AtomicReference<Process?>(null)
    /** 处理器结束等待后拒绝启动其他日志进程。 */
    private val cancelled = AtomicBoolean(false)

    /** 共享 500ms 与总预算，分别记录三个缓冲区的真实状态。 */
    fun collect(pid: Int, apiLevel: Int, overallDeadlineNanos: Long, skipExpensive: Boolean): CrashLogcat {
        val capturedAt = clock()
        if (skipExpensive) return CrashLogcat(status = "skipped", reason = "memory_pressure", capturedAt = capturedAt)
        val deadline = minOf(overallDeadlineNanos, nanoTime() + TimeUnit.MILLISECONDS.toNanos(MAX_WAIT_MILLIS))
        var remaining = LOG_DATA_BYTES
        val main = readBuffer("main", 200, pid, apiLevel, deadline, remaining)
        remaining = (remaining - lineBytes(main.lines)).coerceAtLeast(0)
        val system = readBuffer("system", 50, pid, apiLevel, deadline, remaining)
        remaining = (remaining - lineBytes(system.lines)).coerceAtLeast(0)
        val events = readBuffer("events", 50, pid, apiLevel, deadline, remaining)
        val buffers = listOf(main, system, events)
        val status = when {
            buffers.all { it.status == "ok" } -> "ok"
            buffers.all { it.status == "skipped" } -> "skipped"
            buffers.all { it.status == "unavailable" || it.status == "skipped" } -> "unavailable"
            else -> "partial"
        }
        return CrashLogcat(status = status, reason = buffers.firstOrNull { it.reason != null }?.reason,
            truncated = buffers.any { it.truncated }, capturedAt = capturedAt, main = main, system = system, events = events)
    }

    /** 请求后台清理活动进程，调用线程不等待可能阻塞的流关闭。 */
    fun cancel() {
        cancelled.set(true)
        val process = active.get() ?: return
        runCatching { timer.execute { terminate(process) } }
    }

    /** 关闭采集器并释放其唯一的超时工作线程。 */
    override fun close() {
        cancelled.set(true)
        val process = active.getAndSet(null)
        if (process != null) runCatching { timer.execute { terminate(process) } }
        // 允许已提交的清理执行，避免调用关闭方等待可能阻塞的流。
        timer.shutdown()
    }

    /** 读取一个日志缓冲区，按块限制单行内存并在 UTF-8 完整拼接后解码。 */
    private fun readBuffer(buffer: String, maxLines: Int, pid: Int, apiLevel: Int, deadline: Long, bytesAvailable: Int): LogcatBuffer {
        val capturedAt = clock()
        if (cancelled.get() || nanoTime() >= deadline) return skipped("timeout", capturedAt)
        if (bytesAvailable <= 0) return skipped("size_limit", capturedAt)
        val command = mutableListOf("/system/bin/logcat", "-b", buffer, "-d", "-v", "threadtime", "-t", maxLines.toString())
        if (apiLevel >= 24) command.addAll(listOf("--pid", pid.toString()))
        command.add("*:V")
        val process = try {
            starter(command)
        } catch (_: SecurityException) {
            return LogcatBuffer(status = "unavailable", reason = "permission_denied", capturedAt = capturedAt)
        } catch (_: Throwable) {
            return LogcatBuffer(status = "unavailable", reason = "io_error", capturedAt = capturedAt)
        }
        active.set(process)
        val timedOut = AtomicBoolean(false)
        val delay = (deadline - nanoTime()).coerceAtLeast(0)
        val timeout = try {
            timer.schedule({ timedOut.set(true); terminate(process) }, delay, TimeUnit.NANOSECONDS)
        } catch (_: Throwable) {
            active.compareAndSet(process, null)
            terminate(process)
            return skipped("timeout", capturedAt)
        }
        val lines = ArrayDeque<String>()
        val pending = ByteArrayOutputStream(MAX_RAW_LINE_BYTES)
        var bufferedBytes = 0
        var truncated = false
        var lineTruncated = false
        var permissionFailure = false
        var readFailure = false
        var exitCode: Int? = null

        /** 丢弃超限行尾，脱敏并仅保存可确认属于本 PID 的日志。 */
        fun finishLine() {
            val raw = String(pending.toByteArray(), StandardCharsets.UTF_8).trimEnd('\r')
            pending.reset()
            if (!belongsToPid(raw, pid)) {
                if (raw.contains("permission denied", ignoreCase = true)) permissionFailure = true
                lineTruncated = false
                return
            }
            val redacted = CrashSanitizer.sanitize(raw, MAX_RAW_LINE_BYTES)
            val sanitized = fitLine(redacted)
            val encoded = encodedBytes(sanitized) + 1
            if (lineTruncated || sanitized != redacted) truncated = true
            lineTruncated = false
            while (lines.isNotEmpty() && (lines.size >= maxLines || bufferedBytes + encoded > bytesAvailable)) {
                bufferedBytes -= encodedBytes(lines.removeFirst()) + 1
                truncated = true
            }
            if (encoded <= bytesAvailable) {
                lines.addLast(sanitized)
                bufferedBytes += encoded
            } else truncated = true
        }

        try {
            val input = process.inputStream
            val chunk = ByteArray(CHUNK_BYTES)
            while (!cancelled.get()) {
                val count = input.read(chunk)
                if (count < 0) break
                for (index in 0 until count) {
                    val value = chunk[index]
                    if (value == '\n'.code.toByte()) finishLine()
                    else if (pending.size() < MAX_RAW_LINE_BYTES) pending.write(value.toInt())
                    else lineTruncated = true
                }
            }
            if (pending.size() > 0 || lineTruncated) finishLine()
            if (!cancelled.get()) exitCode = process.waitFor()
        } catch (_: Throwable) {
            readFailure = true
        } finally {
            timeout.cancel(false)
            active.compareAndSet(process, null)
            terminate(process)
        }
        val reason = when {
            timedOut.get() || cancelled.get() || nanoTime() >= deadline -> "timeout"
            permissionFailure && (readFailure || exitCode != 0) -> "permission_denied"
            readFailure || exitCode != 0 -> "io_error"
            truncated -> "size_limit"
            else -> null
        }
        val status = when {
            reason == null -> "ok"
            reason == "size_limit" || lines.isNotEmpty() -> "partial"
            else -> "unavailable"
        }
        return LogcatBuffer(status = status, reason = reason, truncated = truncated,
            capturedAt = capturedAt, lines = lines.toList())
    }

    /** threadtime 的 PID 列独立匹配，防止旧系统通过正文中的数字误收其他进程日志。 */
    private fun belongsToPid(line: String, pid: Int): Boolean =
        PID_PATTERN.find(line)?.groupValues?.get(1)?.toIntOrNull() == pid

    /** 对已脱敏行按 JSON 编码字节二分裁剪，避免拆开 UTF-16 代理对。 */
    private fun fitLine(value: String): String {
        if (encodedBytes(value) <= MAX_JSON_LINE_BYTES) return value
        var low = 0
        var high = value.length
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (encodedBytes(prefix(value, middle)) <= MAX_JSON_LINE_BYTES) low = middle else high = middle - 1
        }
        return prefix(value, low)
    }

    /** 返回不拆分代理对的前缀。 */
    private fun prefix(value: String, length: Int): String {
        var end = length.coerceIn(0, value.length)
        if (end > 0 && end < value.length && Character.isHighSurrogate(value[end - 1]) && Character.isLowSurrogate(value[end])) end--
        return value.substring(0, end)
    }

    /** 计算已保存行的 JSON 编码预算。 */
    private fun lineBytes(lines: List<String>): Int = lines.sumOf { encodedBytes(it) + 1 }

    /** 按上传编码计算对象大小。 */
    private fun encodedBytes(value: Any): Int = gson.toJson(value).toByteArray(StandardCharsets.UTF_8).size

    /** 构造明确未尝试的缓冲区状态。 */
    private fun skipped(reason: String, capturedAt: Long): LogcatBuffer =
        LogcatBuffer(status = "skipped", reason = reason, capturedAt = capturedAt)

    /** 先请求销毁，再关闭所有流；异常内容不进入排障日志。 */
    private fun terminate(process: Process) {
        runCatching { process.destroy() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
    }

    /** 固定命令的资源上限。 */
    private companion object {
        const val MAX_WAIT_MILLIS = 500L
        const val LOG_DATA_BYTES = 46 * 1024
        const val MAX_RAW_LINE_BYTES = 2048
        const val MAX_JSON_LINE_BYTES = 2048
        const val CHUNK_BYTES = 4096
        val PID_PATTERN = Regex("^\\s*\\S+\\s+\\S+\\s+(\\d+)\\s+\\d+\\s+.*")
    }
}
