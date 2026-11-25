package com.shanshui.performance.diagnostics

/** 已脱敏文本及其在本地保存前是否经过裁剪。 */
data class SanitizedDiagnosticText(
    /** 只包含允许保存的有界文本。 */
    val value: String,
    /** 输入或脱敏结果因长度限制而被裁剪。 */
    val truncated: Boolean,
)

/** 与 Crash 共用的诊断文本脱敏规则。 */
object DiagnosticSanitizer {
    /** 覆盖 Android 私有目录、共享存储和开发机用户目录；整行尾部可能含空格或私有文件名。 */
    private val privatePathPattern = Regex(
        "(?i)(?:[A-Z]:\\\\Users\\\\|/data/(?:user(?:_de)?/\\d+|data)/|/storage/emulated/\\d+/|/sdcard/|/mnt/user/\\d+/|/Users/|/home/)[^\\r\\n]*",
    )
    /** 请求头可能包含多个以分号或空格分隔的凭据，应整体移除。 */
    private val sensitiveHeaderPattern = Regex("(?i)\\b(?:authorization|cookie|set-cookie|x[-_]?app[-_]?key|app[-_]?key)\\s*[:=]\\s*[^\\r\\n]*")
    private val emailPattern = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")
    private val phonePattern = Regex("(?<!\\d)\\+?\\d[\\d ()-]{7,}\\d(?!\\d)")
    private val urlPattern = Regex("(?i)\\bhttps?://[^\\s]+")
    private val uuidPattern = Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b")
    private val bearerPattern = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+")
    private val secretAssignmentPattern = Regex(
        "(?i)\\b(cookie|set-cookie|authorization|token|password|secret|api[-_]?key|app[-_]?key)\\s*[:=]\\s*[^\\s,;]+",
    )
    /** 已知上报和查询凭据即使脱离请求头也不能保存。 */
    private val apmCredentialPattern = Regex("(?i)\\bapm_(?:ak|qt)_[A-Za-z0-9_-]+")

    /** 脱敏并限制可保存的原进程文本。 */
    fun sanitize(value: String, maxLength: Int): String = sanitizeWithStatus(value, maxLength).value

    /** 脱敏时保留输入或输出因长度限制被裁剪的事实。 */
    fun sanitizeWithStatus(value: String, maxLength: Int): SanitizedDiagnosticText {
        // 先限制参与规则匹配的输入，避免恶意长线程名或异常消息在核心落盘前无界分配。
        /** 输入长度上限与既有 Crash 脱敏口径一致。 */
        val input = value.take((maxLength.toLong() * 4).coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
        /** 先执行完整脱敏，再计算脱敏后的输出是否超限。 */
        val sanitized = input
            .replace(privatePathPattern, "<redacted-private-path>")
            .replace(sensitiveHeaderPattern, "<redacted-header>")
            .replace(emailPattern, "<redacted-email>")
            .replace(phonePattern, "<redacted-phone>")
            .replace(urlPattern, "<redacted-url>")
            .replace(uuidPattern, "<redacted-uuid>")
            .replace(bearerPattern, "Bearer <redacted>")
            .replace(apmCredentialPattern, "<redacted-credential>")
            .replace(secretAssignmentPattern) { match ->
                "${match.groupValues[1]}=<redacted>"
            }
        return SanitizedDiagnosticText(sanitized.take(maxLength), input.length < value.length || sanitized.length > maxLength)
    }
}
