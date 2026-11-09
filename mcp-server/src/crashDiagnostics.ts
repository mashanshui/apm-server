/** 当前支持的采集状态。 */
const statuses = new Set(['ok', 'partial', 'unavailable', 'skipped'])
/** 接收和展示均使用的安全原因类别。 */
const reasons = new Set(['permission_denied', 'unsupported', 'timeout', 'io_error', 'memory_pressure', 'interrupted', 'size_limit', 'count_limit'])
/** JVM 公开线程状态。 */
const threadStates = new Set(['NEW', 'RUNNABLE', 'BLOCKED', 'WAITING', 'TIMED_WAITING', 'TERMINATED'])
/** 线程还原失败的安全原因。 */
const symbolReasons = new Set(['parser_busy', 'mapping_unavailable', 'timeout', 'interrupted', 'invalid_input', 'invalid_stack', 'empty_output', 'output_limit', 'retrace_failed', 'budget_exhausted'])

/** 只把非数组对象当作 JSON 记录。 */
function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** 可选值允许缺失和 null，但存在时必须满足校验。 */
function optional(value: unknown, valid: (value: unknown) => boolean): boolean {
  return value === null || value === undefined || valid(value)
}

/** 非负整数必须能被 JavaScript 安全表达。 */
function nonNegative(value: unknown): boolean {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

/** 校验可选字符串。 */
function text(value: unknown): boolean { return typeof value === 'string' }
/** 校验可选布尔值。 */
function bool(value: unknown): boolean { return typeof value === 'boolean' }

/** 分区状态、原因、截断和采集时间必须相互一致。 */
function partition(value: unknown): value is Record<string, unknown> {
  if (!record(value) || typeof value.status !== 'string' || !statuses.has(value.status)
      || typeof value.truncated !== 'boolean' || !optional(value.capturedAt, nonNegative)
      || !optional(value.reason, reason => typeof reason === 'string' && reasons.has(reason))) return false
  if (value.status === 'ok') return !value.truncated && (value.reason === null || value.reason === undefined)
  return typeof value.reason === 'string'
}

/** Java ID 保留十进制字符串，避免前端数字精度损失。 */
function javaId(value: unknown): value is string {
  return typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value) && BigInt(value) <= 9223372036854775807n
}

/** 校验单个原始 Java 帧，允许未知文件名和行号。 */
function frame(value: unknown): boolean {
  return record(value) && typeof value.className === 'string' && value.className.length > 0
    && typeof value.methodName === 'string' && value.methodName.length > 0
    && optional(value.fileName, text) && optional(value.lineNumber, number =>
      typeof number === 'number' && Number.isInteger(number) && number >= -1 && number <= 1_000_000_000)
    && optional(value.applicationFrame, bool)
}

/** 校验其他线程独立状态、当前栈和成功时的实际来源。 */
function thread(value: unknown): value is Record<string, unknown> {
  if (!record(value) || !javaId(value.javaId) || typeof value.name !== 'string'
      || typeof value.state !== 'string' || !threadStates.has(value.state)
      || typeof value.priority !== 'number' || !Number.isInteger(value.priority) || value.priority < 1 || value.priority > 10
      || typeof value.daemon !== 'boolean' || typeof value.mainThread !== 'boolean' || typeof value.truncated !== 'boolean'
      || !record(value.stack) || !optional(value.symbolicationReason, text)
      || !optional(value.symbolFileId, text) || !optional(value.symbolFileRevision, nonNegative)
      || !optional(value.symbolFileSha256, text) || !optional(value.processedAt, text)) return false
  if (value.symbolicationStatus === 'symbolicated') {
    return value.stack.format === 'retrace_text' && typeof value.stack.content === 'string' && !!value.stack.content.trim()
      && typeof value.symbolFileId === 'string' && !!value.symbolFileId
      && typeof value.symbolFileRevision === 'number' && value.symbolFileRevision >= 1
      && typeof value.symbolFileSha256 === 'string' && /^[a-f0-9]{64}$/.test(value.symbolFileSha256)
      && typeof value.processedAt === 'string' && !!value.processedAt
  }
  if (!['pending', 'failed', 'not_applicable'].includes(String(value.symbolicationStatus))
      || value.stack.format !== 'raw_frames' || !Array.isArray(value.stack.content)
      || value.stack.content.length > 32 || !value.stack.content.every(frame)) return false
  if (value.symbolicationStatus === 'not_applicable') return value.stack.content.length === 0
  if (value.stack.content.length === 0) return false
  return value.symbolicationStatus !== 'failed' || (typeof value.symbolicationReason === 'string' && symbolReasons.has(value.symbolicationReason))
}

/** 校验 logcat 子缓冲区，区分成功空行与不可用。 */
function logBuffer(value: unknown, limit: number): boolean {
  if (!partition(value) || !Array.isArray(value.lines) || value.lines.length > limit || !value.lines.every(text)) return false
  return !['skipped', 'unavailable'].includes(String(value.status)) || value.lines.length === 0
}

/** 验证完整诊断视图；历史事件使用 null，未知状态或格式拒绝。 */
export function validDiagnostics(value: unknown): boolean {
  if (value === null) return true
  if (!record(value)) return false
  if (!optional(value.coreTruncation, core => record(core) && typeof core.truncated === 'boolean'
      && (core.truncated ? core.reason === 'size_limit' : core.reason === null || core.reason === undefined))) return false
  const ids = new Set<string>()
  if (!optional(value.crashedThread, crashed => {
    if (!partition(crashed) || !optional(crashed.javaId, javaId) || !optional(crashed.name, text)
        || !optional(crashed.state, state => typeof state === 'string' && threadStates.has(state))
        || !optional(crashed.priority, priority => typeof priority === 'number' && Number.isInteger(priority) && priority >= 1 && priority <= 10)
        || !optional(crashed.daemon, bool) || !optional(crashed.mainThread, bool)
        || !optional(crashed.systemTid, number => nonNegative(number) && number !== 0)) return false
    if (crashed.status === 'ok' || crashed.status === 'partial') {
      if (!javaId(crashed.javaId) || typeof crashed.name !== 'string' || typeof crashed.state !== 'string') return false
    }
    if (javaId(crashed.javaId)) ids.add(crashed.javaId)
    return true
  })) return false
  if (!optional(value.otherThreads, threads => {
    if (!partition(threads) || !optional(threads.observedCount, nonNegative) || !optional(threads.omittedCount, nonNegative)
        || !Array.isArray(threads.threads) || threads.threads.length > 64 || !threads.threads.every(thread)) return false
    if (['skipped', 'unavailable'].includes(String(threads.status)) && threads.threads.length) return false
    for (const item of threads.threads) {
      if (!javaId(item.javaId) || ids.has(item.javaId)) return false
      ids.add(item.javaId)
    }
    if (typeof threads.observedCount === 'number' && threads.observedCount < threads.threads.length) return false
    if (typeof threads.observedCount === 'number' && typeof threads.omittedCount === 'number'
        && threads.omittedCount + threads.threads.length > threads.observedCount) return false
    return true
  })) return false
  if (!optional(value.logcat, logs => partition(logs) && optional(logs.main, item => logBuffer(item, 200))
      && optional(logs.system, item => logBuffer(item, 50)) && optional(logs.events, item => logBuffer(item, 50)))) return false
  if (!optional(value.memory, memory => partition(memory)
      && ['javaHeapUsedBytes', 'javaHeapMaxBytes', 'javaHeapPssBytes', 'nativeHeapPssBytes', 'nativeHeapAllocatedBytes',
        'totalPssBytes', 'rssBytes', 'vssBytes', 'swapBytes'].every(key => optional(memory[key], nonNegative))
      && ['meminfo', 'statusText', 'limits'].every(key => optional(memory[key], text)))) return false
  if (!optional(value.openFiles, files => partition(files) && optional(files.count, nonNegative) && Array.isArray(files.entries)
      && files.entries.length <= 128 && files.entries.every(item => record(item) && nonNegative(item.fd) && optional(item.target, text))
      && (!['skipped', 'unavailable'].includes(String(files.status)) || files.entries.length === 0)
      && (typeof files.count !== 'number' || files.count >= files.entries.length))) return false
  if (!optional(value.process, process => partition(process) && optional(process.pid, pid => nonNegative(pid) && pid !== 0)
      && optional(process.name, text) && optional(process.foreground, bool) && optional(process.startedAt, nonNegative)
      && optional(process.sdkInitializedAt, nonNegative))) return false
  return optional(value.device, device => partition(device) && optional(device.apiLevel, api => nonNegative(api) && api !== 0)
    && Array.isArray(device.abis) && device.abis.length <= 16 && device.abis.every(text)
    && optional(device.manufacturer, text) && optional(device.brand, text) && optional(device.buildFingerprint, text))
}
