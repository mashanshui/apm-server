package com.shanshui.performance.anr

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.shanshui.performance.diagnostics.DiagnosticSanitizer
import com.shanshui.performance.network.CrashDevice
import com.shanshui.performance.network.CrashLogcat
import com.shanshui.performance.network.CrashMemory
import com.shanshui.performance.network.CrashProcess
import com.shanshui.performance.network.OpenFiles

/** 按实际 UTF-8 JSON 字节冻结 ANR，重试时不再重新裁剪。 */
internal class AnrEventFactory(
    /** 与发送批次一致的 JSON 编码器。 */
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create(),
) {
    /** 将已提交现场构造为有界事件，未完成分区保留受控原因。 */
    fun freeze(record: AnrCaptureRecord, missingReason: String): AnrUploadEvent {
        /** 核心身份始终使用原采集进程的报告。 */
        val report = record.report
        val identity = report.identity
        val stack = fitString(report.javaStack, STACK_BYTES)
        val complete = completeRecord(record, missingReason)
        val info = fitPartition(requireNotNull(complete.info), INFO_BYTES)
        val trace = fitPartition(requireNotNull(complete.trace), TRACE_BYTES)
        var diagnostics = complete.diagnostics
        if (jsonBytes(diagnostics) > DIAGNOSTICS_BYTES) diagnostics = trimDiagnostics(diagnostics)
        /** 首次冻结后所有重试都读取同一事件对象。 */
        var event = AnrUploadEvent(eventId = report.id, occurredAt = report.createdAtMillis,
            sessionId = identity.sessionId, processId = identity.processId, anonymousDeviceId = identity.deviceId,
            packageName = identity.packageName, appVersion = identity.versionName, versionCode = identity.versionCode,
            buildId = identity.buildId, environment = identity.environment, channel = identity.channel,
            osVersion = record.osVersion, deviceModel = record.deviceModel,
            anr = AnrUploadPayload(source = if (report.fromProcessErrorState == true) "process_error_state" else "message_timeout",
                mainThreadStack = stack.first, mainThreadStackTruncated = report.javaStackTruncated == true || stack.second,
                message = fitString(report.message, AUXILIARY_BYTES).first, messageWhenMillis = report.messageWhenMillis,
                foreground = report.foreground, cgroup = fitString(report.cgroup, AUXILIARY_BYTES).first,
                anrInfo = info, trace = trace, diagnostics = diagnostics))
        if (jsonBytes(event) > EVENT_BYTES) {
            event = event.copy(anr = event.anr.copy(diagnostics = trimDiagnostics(event.anr.diagnostics)))
        }
        require(jsonBytes(event) <= EVENT_BYTES) { "ANR core exceeds event budget" }
        return event
    }

    /** 本地模式只冻结现场，不构造网络事件。 */
    fun completeRecord(record: AnrCaptureRecord, reason: String): AnrCaptureRecord = record.copy(
        info = record.info ?: initialInfo(record.report) ?: unavailable(reason),
        trace = record.trace ?: unavailable(reason),
        diagnostics = completeDiagnostics(record.diagnostics, reason),
    )

    /** 将系统状态判定时已取得的说明纳入核心；旧记录可按同一规则恢复。 */
    fun initialInfo(report: AnrReport, capturedAt: Long? = null): AnrTextPartition? {
        if (report.fromProcessErrorState != true || report.anrInfo.isNullOrBlank()) return null
        /** 再次限制字符数并清理敏感内容，字节预算在冻结时检查。 */
        val safe = DiagnosticSanitizer.sanitizeWithStatus(report.anrInfo, 8 * 1024)
        if (safe.value.isBlank()) return null
        /** 延续 Matrix 回调以及本次脱敏的截断状态。 */
        val truncated = report.anrInfoTruncated == true || safe.truncated
        return AnrTextPartition(status = if (truncated) "partial" else "ok",
            reason = if (truncated) "size_limit" else null, truncated = truncated,
            capturedAt = capturedAt, text = safe.value)
    }

    /** trace 在持久化分区前先受同一 JSON 字节预算约束。 */
    fun boundedTrace(text: String, truncated: Boolean, capturedAt: Long): AnrTextPartition = if (text.isEmpty()) {
        AnrTextPartition("unavailable", "not_found", capturedAt = capturedAt)
    } else fitPartition(AnrTextPartition(status = if (truncated) "partial" else "ok",
            reason = if (truncated) "size_limit" else null, truncated = truncated, capturedAt = capturedAt,
            text = text, source = "art_first_write"), TRACE_BYTES,
    )

    /** 裁剪分区自身的 JSON，包括字段名、转义和多字节字符。 */
    private fun fitPartition(part: AnrTextPartition, maximum: Int): AnrTextPartition {
        if (jsonBytes(part) <= maximum) return part
        val text = part.text ?: return unavailable("size_limit")
        var low = 0
        var high = text.length
        while (low < high) {
            val middle = (low + high + 1) / 2
            val candidate = part.copy(status = "partial", reason = "size_limit", truncated = true,
                text = prefix(text, middle))
            if (jsonBytes(candidate) <= maximum) low = middle else high = middle - 1
        }
        val result = prefix(text, low)
        return if (result.isEmpty()) unavailable("size_limit") else
            part.copy(status = "partial", reason = "size_limit", truncated = true, text = result)
    }

    /** 以 JSON 字符串字节数为依据截取，避免 Unicode 或转义导致服务器拒收。 */
    private fun fitString(value: String?, maximum: Int): Pair<String?, Boolean> {
        if (value == null || jsonBytes(value) <= maximum) return value to false
        var low = 0
        var high = value.length
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (jsonBytes(prefix(value, middle)) <= maximum) low = middle else high = middle - 1
        }
        return prefix(value, low) to true
    }

    /** 不在代理对中间切断 UTF-16 文本。 */
    private fun prefix(value: String, length: Int): String {
        val end = length.coerceIn(0, value.length)
        return value.substring(0, if (end > 0 && end < value.length &&
            Character.isHighSurrogate(value[end - 1]) && Character.isLowSurrogate(value[end])) end - 1 else end)
    }

    /** 缺失分区在冻结时明确标记，进程重启使用 interrupted。 */
    private fun completeDiagnostics(value: AnrDiagnostics, reason: String): AnrDiagnostics = value.copy(
        logcat = value.logcat ?: CrashLogcat(status = "unavailable", reason = reason),
        memory = value.memory ?: CrashMemory(status = "unavailable", reason = reason),
        openFiles = value.openFiles ?: OpenFiles(status = "unavailable", reason = reason),
        process = value.process ?: CrashProcess(status = "unavailable", reason = reason),
        device = value.device ?: CrashDevice(status = "unavailable", reason = reason),
    )

    /** 极端情况下优先保留核心栈和文本分区，通用分区显式标记预算不足。 */
    private fun trimDiagnostics(value: AnrDiagnostics): AnrDiagnostics = value.copy(
        logcat = CrashLogcat(status = "unavailable", reason = "size_limit", truncated = true,
            capturedAt = value.logcat?.capturedAt),
        memory = CrashMemory(status = "unavailable", reason = "size_limit", truncated = true,
            capturedAt = value.memory?.capturedAt),
        openFiles = OpenFiles(status = "unavailable", reason = "size_limit", truncated = true,
            capturedAt = value.openFiles?.capturedAt),
    )

    /** 缺失文本不构造虚假内容。 */
    private fun unavailable(reason: String): AnrTextPartition = AnrTextPartition("unavailable", reason)

    /** 计算未压缩 JSON 的实际 UTF-8 长度。 */
    private fun jsonBytes(value: Any): Int = gson.toJson(value).toByteArray(Charsets.UTF_8).size

    /** 服务器限制略高于客户端总预算。 */
    private companion object {
        const val STACK_BYTES = 24 * 1024
        const val INFO_BYTES = 8 * 1024
        const val TRACE_BYTES = 96 * 1024
        const val DIAGNOSTICS_BYTES = 96 * 1024
        const val AUXILIARY_BYTES = 4 * 1024
        const val EVENT_BYTES = 240 * 1024
    }
}
