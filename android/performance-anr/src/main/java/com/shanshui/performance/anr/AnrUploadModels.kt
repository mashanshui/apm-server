package com.shanshui.performance.anr

/** 已冻结的 Schema v2 ANR 事件及原进程身份。 */
internal data class AnrUploadEvent(
    /** 公共事件协议版本。 */ val schemaVersion: Int = 2,
    /** 与捕获标识相同的事件 ID。 */ val eventId: String,
    /** 公共批次事件类型。 */ val eventType: String = "anr",
    /** 原进程事件时间。 */ val occurredAt: Long,
    /** 原会话身份。 */ val sessionId: String,
    /** 原进程身份。 */ val processId: String,
    /** 匿名设备标识。 */ val anonymousDeviceId: String,
    /** 原应用包名。 */ val packageName: String,
    /** 原应用版本。 */ val appVersion: String,
    /** 原应用版本号。 */ val versionCode: Int,
    /** 原构建标识。 */ val buildId: String,
    /** 原运行环境。 */ val environment: String,
    /** 原渠道。 */ val channel: String,
    /** 原操作系统版本。 */ val osVersion: String,
    /** 原设备型号。 */ val deviceModel: String,
    /** ANR 判定与诊断。 */ val anr: AnrUploadPayload,
)

/** 本次 ANR 的核心字段和独立分区。 */
internal data class AnrUploadPayload(
    /** Matrix 判定来源。 */ val source: String,
    /** 主线程 Java 栈。 */ val mainThreadStack: String?,
    /** 栈是否已裁剪。 */ val mainThreadStackTruncated: Boolean,
    /** 队首消息。 */ val message: String?,
    /** 队首消息的时间差。 */ val messageWhenMillis: Long?,
    /** 判定时前后台状态。 */ val foreground: Boolean?,
    /** 原进程 cgroup。 */ val cgroup: String?,
    /** 系统错误说明。 */ val anrInfo: AnrTextPartition,
    /** ART 首次写入片段。 */ val trace: AnrTextPartition,
    /** Crash 共享的通用诊断。 */ val diagnostics: AnrDiagnostics,
)

/** 采集过程中逐分区原子保存的材料。 */
internal data class AnrCaptureRecord(
    /** 首先持久化的原始核心报告。 */ val report: AnrReport,
    /** 目标地址与凭据摘要；本地模式为空。 */ val target: String?,
    /** 发生时的系统版本。 */ val osVersion: String,
    /** 发生时的设备型号。 */ val deviceModel: String,
    /** 系统错误说明，空表示尚未完成。 */ val info: AnrTextPartition? = null,
    /** 关联 trace，空表示尚未完成。 */ val trace: AnrTextPartition? = null,
    /** 已完成的通用诊断分区。 */ val diagnostics: AnrDiagnostics = AnrDiagnostics(),
    /** 本地报告已删除时禁止后续冻结重新创建诊断副本。 */ val discardEvidence: Boolean = false,
)

/** 待确认的不可变事件和可变重试元数据。 */
internal data class AnrPendingRecord(
    /** 原目标摘要。 */ val target: String,
    /** 已冻结且重试时不能改写的事件。 */ val event: AnrUploadEvent,
    /** 已尝试次数。 */ val attempts: Int = 0,
    /** 下次允许发送的时间。 */ val nextAttemptAtMillis: Long = 0,
)
