import type { CrashDiagnostics, DiagnosticPartition } from './crashDiagnostics'

// 持久化状态与归组类别仅支持当前契约。
export type SymbolicationStatus = 'pending' | 'failed' | 'symbolicated'
export type GroupingKind = 'temporary' | 'resolved'
export type CrashStack = { format: 'raw_chain'; content: CrashPayload }
  | { format: 'native_stack'; content: NativeCurrent } | { format: 'retrace_text'; content: string }

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
  fileName?: string | null
  lineNumber?: number | null
  applicationFrame?: boolean | null
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

/** Native 帧所属模块，ELF Build ID 不等于应用 buildId。 */
export interface NativeModule {
  /** 事件内模块标识。 */ id: string
  /** 文件名。 */ name: string
  /** 脱敏后的可选路径。 */ path?: string | null
  /** 实际 ABI。 */ abi: string
  /** 可取得时的 ELF Build ID。 */ elfBuildId?: string | null
}

/** 相对模块的原始程序计数器与现场已有函数名。 */
export interface NativeFrame {
  /** 帧顺序。 */ index: number
  /** 事件内模块引用。 */ moduleId?: string | null
  /** 规范十六进制相对地址。 */ relativePc?: string | null
  /** 现场已有函数名。 */ functionName?: string | null
  /** 函数内偏移。 */ functionOffset?: string | null
}

/** 有独立采集状态的 Native 回溯。 */
export interface NativeBacktrace extends DiagnosticPartition {
  /** 原始帧顺序。 */ frames: NativeFrame[]
}

/** 已固定的 SO 或 mapping 来源。 */
export interface NativeSource {
  /** 来源类型。 */ type: 'NATIVE_SO' | 'MAPPING'
  /** 来源身份。 */ symbolFileId: string
  /** 固定修订。 */ revision: number
  /** 来源摘要。 */ sha256: string
}

/** Native 物理帧的独立处理结果。 */
export interface NativeResolution {
  /** 处理状态。 */ status: 'pending' | 'failed' | 'symbolicated' | 'not_applicable'
  /** 安全原因。 */ reason?: string | null
  /** 内联调用链。 */ inline?: Array<{ function?: string | null; file?: string | null; line?: number | null; column?: number | null }> | null
  /** 首次成功来源。 */ source?: NativeSource | null
  /** 最近尝试来源。 */ attemptedSource?: NativeSource | null
  /** 最近处理时间。 */ processedAt?: string | null
  /** 临时故障重试时间。 */ retryAt?: string | null
}

/** 结构化帧保留原物理身份。 */
export interface NativeCurrentFrame extends NativeFrame { /** 逐帧结果。 */ resolution: NativeResolution }

/** 带逐帧处理结果的 Native 回溯。 */
export interface NativeCurrentBacktrace extends DiagnosticPartition { /** 原物理帧。 */ frames: NativeCurrentFrame[] }

/** 关联 Java 栈的采集与还原状态。 */
export interface NativeCurrentJava extends DiagnosticPartition {
  /** 处理状态。 */ symbolicationStatus: 'pending' | 'failed' | 'symbolicated' | 'not_applicable'
  /** 安全原因。 */ symbolicationReason?: string | null
  /** 唯一当前栈。 */ stack: { format: 'raw_frames'; content: StackFrame[] } | { format: 'retrace_text'; content: string }
  /** 首次成功来源。 */ source?: NativeSource | null
  /** 最近尝试来源。 */ attemptedSource?: NativeSource | null
  /** 最近处理时间。 */ processedAt?: string | null
  /** 下次重试时间。 */ retryAt?: string | null
}

/** 带逐帧及 Java 独立来源的 Native 当前现场。 */
export interface NativeCurrent extends Omit<NativeCrash, 'backtrace' | 'javaStack'> {
  /** 原物理帧与还原结果。 */ backtrace: NativeCurrentBacktrace
  /** 可靠关联的当前 Java 栈。 */ javaStack?: NativeCurrentJava | null
}

/** Native 主线程现场；无还原文本或内存转储。 */
export interface NativeCrash {
  /** 崩溃进程实际 ABI。 */ abi: string
  /** 崩溃线程系统 TID。 */ systemTid: number
  /** 可取得的线程名。 */ threadName?: string | null
  /** 可判断时是否为主线程。 */ mainThread?: boolean | null
  /** 信号和零值有效的故障地址。 */ signal: {
    number: number; name: string; code: number; codeName?: string | null; faultAddress?: string | null; abortMessage?: string | null
  }
  /** 原始寄存器以字符串无损保留。 */ registers: DiagnosticPartition & { values: Record<string, string> }
  /** 主辅帧涉及的模块。 */ modules: NativeModule[]
  /** 原始 Native 主回溯。 */ backtrace: NativeBacktrace
  /** 可可靠关联时的 Java 主线程栈。 */ javaStack?: (DiagnosticPartition & { frames: StackFrame[] }) | null
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
  /** Native 主栈的逐状态物理帧数量。 */
  nativeFrameCounts?: { symbolicated: number; pending: number; failed: number; notApplicable: number } | null
  /** 现场未采集时为 null。 */
  diagnostics: CrashDiagnostics | null
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
