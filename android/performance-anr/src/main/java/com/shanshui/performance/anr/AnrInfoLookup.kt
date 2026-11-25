package com.shanshui.performance.anr

import com.shanshui.performance.diagnostics.DiagnosticSanitizer
import java.util.concurrent.TimeUnit

/** 系统错误记录的最小可测试快照。 */
internal data class AnrErrorState(
    /** 记录所属 UID。 */ val uid: Int,
    /** 记录所属 PID。 */ val pid: Int,
    /** 记录是否为应用无响应。 */ val notResponding: Boolean,
    /** 系统给出的详细说明。 */ val longMessage: String?,
)

/** 在有限时间内读取本进程错误说明，不改变 Matrix 判定结果。 */
internal class AnrInfoLookup(
    /** 读取系统当前可见的进程错误状态。 */ private val read: () -> List<AnrErrorState>,
    /** 本进程 UID。 */ private val uid: Int,
    /** 本进程 PID。 */ private val pid: Int,
    /** 捕获窗口是否仍可唯一归属。 */ private val unambiguous: (String) -> Boolean,
    /** 原进程墙上时间。 */ private val clock: () -> Long = System::currentTimeMillis,
    /** 超时使用单调时间。 */ private val nanoTime: () -> Long = System::nanoTime,
    /** 可替换的轮询等待。 */ private val sleep: (Long) -> Unit = Thread::sleep,
) {
    /** 返回匹配的系统说明；消息判定分支允许在截止时间前补查。 */
    fun collect(captureId: String, initial: String?, followUp: Boolean, deadlineNanos: Long): AnrTextPartition {
        if (!unambiguous(captureId)) return unavailable("ambiguous")
        if (!initial.isNullOrBlank()) return available(initial)
        if (!followUp) return unavailable("not_found")
        while (nanoTime() < deadlineNanos) {
            if (!unambiguous(captureId)) return unavailable("ambiguous")
            /** 只接受同时匹配 UID、PID 和 NOT_RESPONDING 的记录。 */
            val records = try {
                read()
            } catch (_: SecurityException) {
                return unavailable("permission_denied")
            } catch (_: Exception) {
                return unavailable("io_error")
            }
            /** 多条匹配记录不能猜测同次采集归属。 */
            val matches = records.filter { it.uid == uid && it.pid == pid && it.notResponding }
            if (matches.size > 1) return unavailable("ambiguous")
            val message = matches.singleOrNull()?.longMessage
            if (!message.isNullOrBlank()) {
                if (!unambiguous(captureId)) return unavailable("ambiguous")
                return available(message)
            }
            /** 避免等待超过采集截止时间。 */
            val remaining = TimeUnit.NANOSECONDS.toMillis((deadlineNanos - nanoTime()).coerceAtLeast(0))
            if (remaining <= 0) break
            try {
                sleep(minOf(POLL_INTERVAL_MILLIS, remaining))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return unavailable("interrupted")
            }
        }
        return unavailable("not_found")
    }

    /** 脱敏并保留采集时间及裁剪状态。 */
    private fun available(value: String): AnrTextPartition {
        /** 与 Crash 一致的本地脱敏及字符预算。 */
        val safe = DiagnosticSanitizer.sanitizeWithStatus(value, MAX_INFO_CHARS)
        return if (safe.value.isEmpty()) unavailable("not_found") else AnrTextPartition(
            status = if (safe.truncated) "partial" else "ok",
            reason = if (safe.truncated) "size_limit" else null,
            truncated = safe.truncated,
            capturedAt = clock(),
            text = safe.value,
        )
    }

    /** 保留不可用原因，不将缺失伪装成空文本。 */
    private fun unavailable(reason: String): AnrTextPartition =
        AnrTextPartition(status = "unavailable", reason = reason, capturedAt = clock())

    /** ANR_INFO 的本地预算和后台补查间隔。 */
    private companion object {
        /** 本地说明最大字符数，冻结时还会验证实际 JSON 字节。 */ const val MAX_INFO_CHARS = 8 * 1024
        /** 后台补查间隔，单位毫秒。 */ const val POLL_INTERVAL_MILLIS = 250L
    }
}
