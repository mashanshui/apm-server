import type { StackFrame } from '../types/crash'
import type { DiagnosticPartition } from '../types/crashDiagnostics'
import { formatDateTime } from './format'

/** 用中文解释分区状态，缺失、失败与截断互不混淆。 */
export function partitionText(partition: DiagnosticPartition | null | undefined): string {
  if (!partition) return '未采集'
  const labels = { ok: '采集成功', partial: '部分采集', unavailable: '不可用', skipped: '已跳过' }
  const reason = partition.reason ? ` · ${reasonText(partition.reason)}` : ''
  const truncated = partition.truncated ? ' · 已截断' : ''
  return `${labels[partition.status]}${reason}${truncated}`
}

/** 将安全原因类别转换为简短中文说明。 */
export function reasonText(reason: string | null | undefined): string {
  const labels: Record<string, string> = {
    permission_denied: '权限受限', unsupported: '系统不支持', timeout: '采集超时', io_error: '读取失败',
    memory_pressure: '内存不足', interrupted: '采集被中断', size_limit: '大小受限', count_limit: '数量受限',
    native_symbolication_deferred: '等待后台处理', parser_busy: '还原资源繁忙', missing_mapping: '等待 mapping', mapping_unavailable: 'mapping 不可用', invalid_input: '还原输入无效',
    invalid_stack: '堆栈无效', empty_output: '还原结果为空', output_limit: '还原结果超限',
    retrace_failed: '还原失败', budget_exhausted: '还原时间不足',
  }
  return reason ? (labels[reason] ?? '处理失败') : '无'
}

/** 明确展示每个线程自己的还原状态。 */
export function threadStatus(status: string): string {
  const labels: Record<string, string> = { pending: '等待还原', failed: '还原失败 · 保留原始帧', symbolicated: '已还原', not_applicable: '无帧 · 不适用还原' }
  return labels[status] ?? '状态未知'
}

/** 将可选字节数同时显示为人类可读单位与精确字节数。 */
export function bytes(value: unknown): string {
  if (typeof value !== 'number') return '—'
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KiB（${value.toLocaleString('zh-CN')} B）`
  return `${(value / (1024 * 1024)).toFixed(1)} MiB（${value.toLocaleString('zh-CN')} B）`
}

/** 采集时间是 Unix 毫秒，无法表示的值不伪造日期。 */
export function timestamp(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : formatDateTime(date.toISOString())
}

/** 将 Java 帧格式化为文本，不生成缺失的调用帧。 */
export function frameText(frame: StackFrame): string {
  const file = frame.fileName ?? '未知文件'
  const line = frame.lineNumber !== null && frame.lineNumber !== undefined && frame.lineNumber >= 0 ? String(frame.lineNumber) : '未知行号'
  return `${frame.className}.${frame.methodName}（${file}:${line}）`
}
