// 持久化状态与归组类别仅支持当前契约。
export type SymbolicationStatus = 'pending' | 'failed' | 'symbolicated'
export type GroupingKind = 'temporary' | 'resolved'
export type CrashStack = { format: 'raw_chain'; content: CrashPayload } | { format: 'retrace_text'; content: string }

export type CrashStatus = 'ok' | 'no_data' | 'denominator_insufficient' | string

export interface CrashStats {
  startedSessions: number
  crashEvents: number
  crashedSessions: number
  affectedDevices: number
  crashRatePer1000Sessions: number | null
  crashFreeSessionRate: number | null
  status: CrashStatus
}

export interface CrashOverviewResponse {
  appId: string
  from: string
  to: string
  stats: CrashStats
  dataSource: string
}

export interface CrashTrendPoint {
  bucketStart: string
  bucketEnd: string
  stats: CrashStats
}

export interface CrashTrendResponse {
  appId: string
  from: string
  to: string
  interval: 'hour' | 'day' | string
  points: CrashTrendPoint[]
  dataSource: string
}

export interface CrashIssueSummary {
  /** 临时问题按构建隔离；正式问题不附加构建身份。 */
  groupingKind: GroupingKind
  buildId: string | null
  fingerprint: string
  fingerprintVersion: string
  exceptionType: string
  eventCount: number
  crashedSessionCount: number
  affectedDeviceCount: number
  firstSeenAt: string
  lastSeenAt: string
}

export interface CrashIssueResponse {
  appId: string
  from: string
  to: string
  issues: CrashIssueSummary[]
  nextCursor: string | null
  dataSource: string
}

export interface CrashEventSummary {
  eventId: string
  occurredAt: string
  appVersion: string
  versionCode: number | null
  buildId: string
  channel: string
  environment: string
  osVersion: string
  deviceModel: string
  sessionId: string | null
  anonymousDeviceId: string | null
  exceptionType: string
  fingerprint: string
  symbolicationStatus: SymbolicationStatus
  groupingKind: GroupingKind
}

export interface CrashEventListResponse {
  appId: string
  fingerprint: string
  from: string
  to: string
  events: CrashEventSummary[]
  nextCursor: string | null
  dataSource: string
}

export interface StackFrame {
  className: string
  methodName: string
  fileName: string
  lineNumber: number | null
  applicationFrame: boolean | null
}

export interface ThrowableNode {
  type: string
  message: string | null
  frames: StackFrame[]
}

export interface CrashPayload {
  kind: string
  fatal: boolean
  throwableChain: ThrowableNode[]
}

export interface CrashEventDetailResponse {
  appId: string
  eventId: string
  packageName: string
  occurredAt: string
  receivedAt: string
  sessionId: string | null
  processId: string | null
  anonymousDeviceId: string | null
  appVersion: string
  versionCode: number | null
  buildId: string
  channel: string
  environment: string
  osVersion: string
  deviceModel: string
  networkType: string | null
  exceptionType: string
  fingerprint: string
  fingerprintVersion: string
  symbolicationStatus: SymbolicationStatus
  groupingKind: GroupingKind
  /** 唯一堆栈，成功时只返回完整还原文本。 */
  stack: CrashStack
  crashKind: string
  fatal: boolean
  symbolFileSha256: string | null
  processedAt: string | null
  symbolFileId: string | null
  symbolFileRevision: number | null
  symbolicationReason: string | null
}

export interface CrashQueryFilters {
  from?: string
  to?: string
  appVersion?: string
  channel?: string
  environment?: string
  osVersion?: string
  deviceModel?: string
  fingerprint?: string
  limit?: number
  cursor?: string
  timeoutMs?: number
}

export interface CrashFilterForm {
  from: string
  to: string
  appVersion: string
  channel: string
  environment: string
  osVersion: string
  deviceModel: string
  fingerprint: string
  interval: 'hour' | 'day'
}
