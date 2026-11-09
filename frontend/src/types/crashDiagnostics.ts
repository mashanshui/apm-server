import type { StackFrame } from './crash'

/** 设备现场各分区的采集状态。 */
export type DiagnosticStatus = 'ok' | 'partial' | 'unavailable' | 'skipped'
/** 安全的采集失败和截断原因。 */
export type DiagnosticReason = 'permission_denied' | 'unsupported' | 'timeout' | 'io_error' |
  'memory_pressure' | 'interrupted' | 'size_limit' | 'count_limit'

/** 每个诊断分区共同携带的采集元数据。 */
export interface DiagnosticPartition {
  /** 实际采集状态。 */ status: DiagnosticStatus
  /** 安全原因类别。 */ reason?: DiagnosticReason | null
  /** 是否发生源端裁剪。 */ truncated: boolean
  /** 原采集时间，Unix 毫秒。 */ capturedAt?: number | null
}

/** 崩溃线程身份，不重复主异常堆栈。 */
export interface CrashedThread extends DiagnosticPartition {
  /** Java 线程 ID，十进制字符串。 */ javaId?: string | null
  /** 已脱敏线程名。 */ name?: string | null
  /** Java 线程状态。 */ state?: string | null
  /** Java 线程优先级。 */ priority?: number | null
  /** 是否守护线程。 */ daemon?: boolean | null
  /** 是否 Android 主线程。 */ mainThread?: boolean | null
  /** 系统线程 TID。 */ systemTid?: number | null
}

/** 其他线程当前唯一堆栈。 */
export type DiagnosticThreadStack =
  | { format: 'raw_frames'; content: StackFrame[] }
  | { format: 'retrace_text'; content: string }

/** 一个其他线程的身份和独立还原结果。 */
export interface DiagnosticThread {
  /** Java 线程 ID。 */ javaId: string
  /** 已脱敏线程名。 */ name: string
  /** Java 线程状态。 */ state: string
  /** Java 线程优先级。 */ priority: number
  /** 是否守护线程。 */ daemon: boolean
  /** 是否 Android 主线程。 */ mainThread: boolean
  /** 源端是否裁剪该线程帧。 */ truncated: boolean
  /** 当前唯一堆栈。 */ stack: DiagnosticThreadStack
  /** 此线程独立处理状态。 */ symbolicationStatus: 'pending' | 'failed' | 'symbolicated' | 'not_applicable'
  /** 安全处理原因。 */ symbolicationReason?: string | null
  /** 实际 mapping 身份。 */ symbolFileId?: string | null
  /** 实际 mapping 版本。 */ symbolFileRevision?: number | null
  /** 实际 mapping 摘要。 */ symbolFileSha256?: string | null
  /** 实际处理时间。 */ processedAt?: string | null
}

/** 有界线程集合及已知计数。 */
export interface DiagnosticThreads extends DiagnosticPartition {
  /** 可知的其他线程总数。 */ observedCount?: number | null
  /** 可知的遗漏线程数。 */ omittedCount?: number | null
  /** 依原采集顺序返回的线程。 */ threads: DiagnosticThread[]
}

/** 已脱敏的单个 logcat 缓冲区。 */
export interface DiagnosticLogBuffer extends DiagnosticPartition {
  /** 当前进程可取得的日志行。 */ lines: string[]
}

/** main、system 和 events 各自保留采集状态。 */
export interface DiagnosticLogcat extends DiagnosticPartition {
  /** main 缓冲区。 */ main?: DiagnosticLogBuffer | null
  /** system 缓冲区。 */ system?: DiagnosticLogBuffer | null
  /** events 缓冲区。 */ events?: DiagnosticLogBuffer | null
}

/** 内存数值的单位均为字节，空值表示未知。 */
export interface DiagnosticMemory extends DiagnosticPartition {
  /** Runtime Java 堆已用量。 */ javaHeapUsedBytes?: number | null
  /** Runtime Java 堆最大量。 */ javaHeapMaxBytes?: number | null
  /** Debug Java 堆 PSS。 */ javaHeapPssBytes?: number | null
  /** Debug Native 堆 PSS。 */ nativeHeapPssBytes?: number | null
  /** Native 堆已分配量。 */ nativeHeapAllocatedBytes?: number | null
  /** 进程总 PSS。 */ totalPssBytes?: number | null
  /** 进程 RSS。 */ rssBytes?: number | null
  /** 进程 VSS。 */ vssBytes?: number | null
  /** 进程 Swap。 */ swapBytes?: number | null
  /** 脱敏的 meminfo。 */ meminfo?: string | null
  /** 脱敏的 proc status。 */ statusText?: string | null
  /** 脱敏的 proc limits。 */ limits?: string | null
}

/** 文件描述符总数和有限明细。 */
export interface DiagnosticOpenFiles extends DiagnosticPartition {
  /** 可知的描述符总数。 */ count?: number | null
  /** 已脱敏的编号及目标类别。 */ entries: Array<{ fd: number; target?: string | null }>
}

/** 进程上下文，未知前后台和启动时间保留为空。 */
export interface DiagnosticProcess extends DiagnosticPartition {
  /** Android 数值 PID。 */ pid?: number | null
  /** 已脱敏进程名。 */ name?: string | null
  /** 已观察到的前后台状态。 */ foreground?: boolean | null
  /** 可靠取得的进程启动时间。 */ startedAt?: number | null
  /** SDK 初始化时间。 */ sdkInitializedAt?: number | null
}

/** 可公开获取的设备环境。 */
export interface DiagnosticDevice extends DiagnosticPartition {
  /** Android API level。 */ apiLevel?: number | null
  /** 支持的 ABI。 */ abis: string[]
  /** 设备厂商。 */ manufacturer?: string | null
  /** 设备品牌。 */ brand?: string | null
  /** 系统构建指纹。 */ buildFingerprint?: string | null
}

/** 详情返回的诊断上下文；整个对象为 null 表示未采集。 */
export interface CrashDiagnostics {
  /** 主异常是否在源端被裁剪。 */ coreTruncation?: { truncated: boolean; reason?: string | null } | null
  /** 崩溃线程。 */ crashedThread?: CrashedThread | null
  /** 其他线程。 */ otherThreads?: DiagnosticThreads | null
  /** 日志。 */ logcat?: DiagnosticLogcat | null
  /** 内存。 */ memory?: DiagnosticMemory | null
  /** 文件描述符。 */ openFiles?: DiagnosticOpenFiles | null
  /** 进程。 */ process?: DiagnosticProcess | null
  /** 设备。 */ device?: DiagnosticDevice | null
}
