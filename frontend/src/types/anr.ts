import type { CrashDiagnostics } from './crashDiagnostics'

/** 当前筛选绑定的查询条件。 */
export interface AnrFilters {
  /** 包含的起始时间。 */ from: string
  /** 不包含的结束时间。 */ to: string
  /** 可选应用版本。 */ appVersion: string
  /** 可选 Matrix 判定来源。 */ source: '' | 'message_timeout' | 'process_error_state'
}

/** 当前筛选范围内的事件和去重设备数量。 */
export interface AnrOverview {
  /** 应用 ID。 */ appId: string
  /** 包含的起始时间。 */ from: string
  /** 不包含的结束时间。 */ to: string
  /** 已接收的逻辑事件数。 */ eventCount: number
  /** 受影响的去重设备数。 */ deviceCount: number
}

/** 列表中的有限事件摘要。 */
export interface AnrEventSummary {
  /** 原事件 ID。 */ eventId: string
  /** 原发生时间。 */ occurredAt: string
  /** 应用版本。 */ appVersion: string
  /** 版本号。 */ versionCode: number | null
  /** 设备型号。 */ deviceModel: string
  /** Matrix 判定来源。 */ source: AnrFilters['source']
}

/** 后端返回的游标分页。 */
export interface AnrEventPage {
  /** 应用 ID。 */ appId: string
  /** 包含的起始时间。 */ from: string
  /** 不包含的结束时间。 */ to: string
  /** 当前页摘要。 */ events: AnrEventSummary[]
  /** 不透明续页游标。 */ nextCursor: string | null
}

/** 单个文本分区的源端状态。 */
export interface AnrTextPartition {
  /** 实际采集状态。 */ status: 'ok' | 'partial' | 'unavailable' | 'skipped'
  /** 失败或截断原因。 */ reason?: string | null
  /** 是否已知截断。 */ truncated: boolean
  /** 原采集时间。 */ capturedAt?: number | null
  /** 已脱敏原文。 */ text?: string | null
  /** trace 的固定来源。 */ source?: string | null
}

/** 后台 Java 还原结果和实际 mapping 来源。 */
export interface AnrJavaView {
  /** 独立分区的处理状态。 */ status: 'pending' | 'failed' | 'partial' | 'symbolicated' | 'not_applicable'
  /** 安全处理原因。 */ reason?: string | null
  /** 可用的还原文本。 */ text?: string | null
  /** 符号文件 ID。 */ symbolFileId?: string | null
  /** 符号文件修订。 */ symbolFileRevision?: number | null
  /** 符号文件摘要。 */ symbolFileSha256?: string | null
  /** 处理时间。 */ processedAt?: string | null
}

/** 单个 ANR 的原始身份和现场。 */
export interface AnrEventDetail {
  /** 原事件元数据。 */ metadata: {
    eventId: string; occurredAt: string; receivedAt: string; packageName: string; appVersion: string
    versionCode: number | null; buildId: string; osVersion: string; deviceModel: string
    environment: string; channel: string; sessionId: string | null; processId: string | null
    anonymousDeviceId: string | null
  }
  /** 不可变原始 ANR 载荷。 */ anr: {
    source: AnrFilters['source']; mainThreadStack?: string | null; mainThreadStackTruncated: boolean
    message?: string | null; messageWhenMillis?: number | null; foreground?: boolean | null; cgroup?: string | null
    anrInfo?: AnrTextPartition | null; trace?: AnrTextPartition | null; diagnostics?: CrashDiagnostics | null
  }
  /** 主线程 Java 还原状态。 */ mainThreadJava: AnrJavaView
  /** trace 中 Java 帧还原状态。 */ traceJava: AnrJavaView
}
