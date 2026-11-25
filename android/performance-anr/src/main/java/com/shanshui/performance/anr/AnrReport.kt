package com.shanshui.performance.anr

import com.google.gson.annotations.SerializedName

/** Matrix 实际回调或 trace 的来源。 */
enum class AnrReportType {
    /** 外部 SIGQUIT 经 Matrix 判定后的 ANR 回调。 */
    @SerializedName("SIGNAL_ANR") SIGNAL_ANR,
    /** debugger 信号对应的主线程 Java 栈回调。 */
    @SerializedName("DEBUGGER_SIGNAL") DEBUGGER_SIGNAL,
    /** Matrix 保存的外部 SIGQUIT trace。 */
    @SerializedName("ANR_TRACE") ANR_TRACE,
    /** Matrix 保存的手动 trace，不代表系统 ANR。 */
    @SerializedName("MANUAL_TRACE") MANUAL_TRACE,
    /** Matrix 锁环解析回调。 */
    @SerializedName("DEADLOCK") DEADLOCK,
    /** Matrix nativePollOnce 解析回调。 */
    @SerializedName("NATIVE_POLL_ONCE") NATIVE_POLL_ONCE,
}

/** 事件原进程共享的身份和应用元数据。 */
data class AnrReportIdentity(
    /** 匿名设备标识。 */
    @SerializedName("deviceId") val deviceId: String,
    /** 原运行会话标识。 */
    @SerializedName("sessionId") val sessionId: String,
    /** 原进程标识。 */
    @SerializedName("processId") val processId: String,
    /** 应用包名。 */
    @SerializedName("packageName") val packageName: String,
    /** 原运行版本名。 */
    @SerializedName("versionName") val versionName: String,
    /** 原运行版本号。 */
    @SerializedName("versionCode") val versionCode: Int,
    /** 构建标识。 */
    @SerializedName("buildId") val buildId: String,
    /** 环境名称。 */
    @SerializedName("environment") val environment: String,
    /** 渠道名称。 */
    @SerializedName("channel") val channel: String,
)

/** 原样保存 Matrix 回调中实际提供的字段。 */
data class AnrReport(
    /** 本地报告唯一标识。 */
    @SerializedName("id") val id: String,
    /** 结果生成时间。 */
    @SerializedName("createdAtMillis") val createdAtMillis: Long,
    /** 原始回调来源。 */
    @SerializedName("type") val type: AnrReportType,
    /** 原进程身份快照。 */
    @SerializedName("identity") val identity: AnrReportIdentity,
    /** 外部信号的判定与 trace 共用的采集身份。 */
    @SerializedName("captureId") val captureId: String? = null,
    /** Matrix 采集的主线程 Java 栈。 */
    @SerializedName("javaStack") val javaStack: String? = null,
    /** 本地保存前主线程栈是否已裁剪。 */
    @SerializedName("javaStackTruncated") val javaStackTruncated: Boolean? = null,
    /** Matrix 读取的主线程消息。 */
    @SerializedName("message") val message: String? = null,
    /** Matrix 计算的消息时间差，单位毫秒。 */
    @SerializedName("messageWhenMillis") val messageWhenMillis: Long? = null,
    /** Matrix 回调是否来自系统进程错误状态。 */
    @SerializedName("fromProcessErrorState") val fromProcessErrorState: Boolean? = null,
    /** 判定时采集的前后台状态。 */
    @SerializedName("foreground") val foreground: Boolean? = null,
    /** 可归属同次采集的系统错误说明。 */
    @SerializedName("anrInfo") val anrInfo: String? = null,
    /** 本地保存前系统错误说明是否已裁剪。 */
    @SerializedName("anrInfoTruncated") val anrInfoTruncated: Boolean? = null,
    /** 冻结后的系统说明分区状态。 */
    @SerializedName("anrInfoSection") val anrInfoSection: AnrTextPartition? = null,
    /** Matrix 采集的 cgroup 内容。 */
    @SerializedName("cgroup") val cgroup: String? = null,
    /** Matrix 首次写入截取并脱敏后的 trace。 */
    @SerializedName("trace") val trace: String? = null,
    /** 已脱敏的 trace 文本按 UTF-8 编码后的 Base64 表示。 */
    @SerializedName("traceBase64") val traceBase64: String? = null,
    /** Native 封存或本地脱敏时是否因长度上限裁剪。 */
    @SerializedName("traceTruncated") val traceTruncated: Boolean? = null,
    /** 冻结后的系统 trace 分区状态。 */
    @SerializedName("traceSection") val traceSection: AnrTextPartition? = null,
    /** 冻结后的内存、日志、文件和设备现场。 */
    @SerializedName("diagnostics") val diagnostics: AnrDiagnostics? = null,
    /** Matrix 锁解析返回的主线程文本。 */
    @SerializedName("mainThread") val mainThread: String? = null,
    /** Matrix 锁解析返回的第一个线程文本。 */
    @SerializedName("lockThread1") val lockThread1: String? = null,
    /** Matrix 锁解析返回的第二个线程文本。 */
    @SerializedName("lockThread2") val lockThread2: String? = null,
    /** Matrix 锁解析返回的等待线程 ID。 */
    @SerializedName("waitingThreadIds") val waitingThreadIds: List<Int>? = null,
    /** Matrix 锁解析返回的锁类型。 */
    @SerializedName("waitingLockTypes") val waitingLockTypes: List<String?>? = null,
)

/** 列表仅读取的报告摘要，不携带 trace 正文。 */
data class AnrReportSummary(
    /** 本地报告唯一标识。 */
    @SerializedName("id") val id: String,
    /** 结果生成时间。 */
    @SerializedName("createdAtMillis") val createdAtMillis: Long,
    /** Matrix 结果来源。 */
    @SerializedName("type") val type: AnrReportType,
    /** 已保存正文的字节数。 */
    @SerializedName("sizeBytes") val sizeBytes: Long,
    /** 外部信号的采集身份，历史报告或手动报告可为空。 */
    @SerializedName("captureId") val captureId: String? = null,
)

/** 报告列表及下一页位置。 */
data class AnrReportPage(
    /** 当前页的摘要。 */
    val reports: List<AnrReportSummary>,
    /** 后续页面从此报告之后继续；null 表示没有下一页。 */
    val nextAfterId: String?,
)

/** 本地报告操作的可识别错误。 */
enum class AnrReportError {
    /** 报告标识不符合本模块生成的 UUID 格式。 */
    INVALID_ID,
    /** 指定报告不存在。 */
    NOT_FOUND,
    /** 索引或报告内容损坏。 */
    CORRUPT,
    /** 文件系统操作失败。 */
    IO_ERROR,
    /** 单条报告超过配置的容量上限。 */
    TOO_LARGE,
    /** 页大小或游标无效。 */
    INVALID_PAGE,
}

/** 本地报告读取、列表或删除结果。 */
sealed class AnrReportResult<out T> {
    /** 操作成功并返回实际结果。 */
    data class Success<T>(
        /** 操作返回的值。 */
        val value: T,
    ) : AnrReportResult<T>()

    /** 操作失败，原因可供宿主区分。 */
    data class Failure(
        /** 可供调用方区分的失败原因。 */
        val error: AnrReportError,
    ) : AnrReportResult<Nothing>()
}

/** 报告成功保存后调用的 SDK 回调。 */
fun interface AnrReportListener {
    /** 接收已提交报告的摘要。 */
    fun onReportSaved(report: AnrReportSummary)
}
