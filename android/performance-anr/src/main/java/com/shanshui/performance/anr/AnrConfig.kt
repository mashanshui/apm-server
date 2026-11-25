package com.shanshui.performance.anr

/** ANR 客户端采集配置；启用采集必须由宿主显式决定。 */
data class AnrConfig(
    /** 是否在受支持的主进程启用 ANR 采集。 */
    val enabled: Boolean = false,
    /** 最多保留的已完成报告数量。 */
    val maxReportCount: Int = 20,
    /** 已完成报告正文总字节数上限。 */
    val maxStorageBytes: Long = 20L * 1024 * 1024,
    /** 已完成报告的最长保留时间。 */
    val maxReportAgeMillis: Long = 7L * 24 * 60 * 60 * 1000,
    /** 上传必须由宿主单独显式启用。 */
    val uploadEnabled: Boolean = false,
    /** 后台上传检查间隔，单位毫秒。 */
    val uploadIntervalMillis: Long = 30_000L,
) {
    /** 校验本地报告容量，避免错误配置影响其他功能目录。 */
    init {
        require(maxReportCount in 1..200) { "maxReportCount must be between 1 and 200" }
        require(maxStorageBytes > 0) { "maxStorageBytes must be positive" }
        require(maxReportAgeMillis > 0) { "maxReportAgeMillis must be positive" }
        require(uploadIntervalMillis > 0) { "uploadIntervalMillis must be positive" }
    }
}

/** ANR 监控初始化结果，不把平台条件满足等同于监控已启动。 */
enum class AnrInitStatus {
    /** 宿主未启用采集。 */
    DISABLED,
    /** Android API 版本低于采集下限。 */
    UNSUPPORTED_API,
    /** 当前进程架构不支持 ANR 原生采集。 */
    UNSUPPORTED_ABI,
    /** 当前进程不是应用主进程。 */
    NOT_MAIN_PROCESS,
    /** 系统信息或采集资源初始化失败。 */
    FAILED,
    /** 完整采集能力已经启动。 */
    READY,
    /** 部分信号来源或证据采集不可用。 */
    DEGRADED,
}

/** 对外暴露的初始化状态与不含业务数据的原因。 */
data class AnrInitResult(
    /** ANR 采集的当前状态。 */
    val status: AnrInitStatus,
    /** 不可用或降级时可安全记录的原因。 */
    val reason: String? = null,
)
