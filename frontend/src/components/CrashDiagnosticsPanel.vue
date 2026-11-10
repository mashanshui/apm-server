<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { NativeCrash, StackFrame } from '../types/crash'
import type { CrashDiagnostics, DiagnosticLogBuffer, DiagnosticMemory, DiagnosticPartition } from '../types/crashDiagnostics'
import { formatDateTime } from '../utils/format'
import NativeFrameList from './NativeFrameList.vue'

/** 当前事件已经授权返回的诊断现场。 */
const props = defineProps<{ diagnostics: CrashDiagnostics | null; nativeCrash?: NativeCrash | null }>()
/** 当前展开的 Java 线程身份。 */
const selectedThreadId = ref<string | null>(null)
/** Native 线程按系统 TID 选择，不能与 Java ID 或线程名混同。 */
const selectedNativeTid = ref<number | null>(null)
/** 当前查看的日志缓冲区。 */
const selectedBuffer = ref<'main' | 'system' | 'events'>('main')
/** 按采集顺序保留同名线程。 */
const threads = computed(() => props.diagnostics?.otherThreads?.threads ?? [])
/** 当前选中的线程。 */
const selectedThread = computed(() => threads.value.find(thread => thread.javaId === selectedThreadId.value) ?? null)
/** 原生线程保留采集顺序。 */
const nativeThreads = computed(() => props.diagnostics?.nativeThreads?.threads ?? [])
/** 当前选择的原生线程。 */
const selectedNativeThread = computed(() => nativeThreads.value.find(thread => thread.systemTid === selectedNativeTid.value) ?? null)
/** 固定缓冲区名称和展示标签。 */
const buffers = [{ key: 'main', label: 'Main' }, { key: 'system', label: 'System' }, { key: 'events', label: 'Events' }] as const
/** 当前缓冲区可能未采集。 */
const logBuffer = computed<DiagnosticLogBuffer | null>(() => props.diagnostics?.logcat?.[selectedBuffer.value] ?? null)
/** 内存指标名称明确区分 Runtime 堆和 Debug PSS。 */
const memoryMetrics: Array<{ key: keyof DiagnosticMemory; label: string }> = [
  { key: 'javaHeapUsedBytes', label: 'Runtime Java 堆已用' },
  { key: 'javaHeapMaxBytes', label: 'Runtime Java 堆上限' },
  { key: 'javaHeapPssBytes', label: 'Java 堆 PSS' },
  { key: 'nativeHeapPssBytes', label: 'Native 堆 PSS' },
  { key: 'nativeHeapAllocatedBytes', label: 'Native 堆已分配' },
  { key: 'totalPssBytes', label: '进程总 PSS' },
  { key: 'rssBytes', label: '进程 RSS' },
  { key: 'vssBytes', label: '进程 VSS' },
  { key: 'swapBytes', label: '进程 Swap' },
]
/** 可展开的有界 proc 文本。 */
const memoryTexts = [{ key: 'meminfo', label: 'meminfo' }, { key: 'statusText', label: '进程 status' }, { key: 'limits', label: '进程 limits' }] as const

// 事件变化后优先显示主线程，并清除旧的日志选择。
watch(() => props.diagnostics, () => {
  selectedThreadId.value = threads.value.find(thread => thread.mainThread)?.javaId ?? threads.value[0]?.javaId ?? null
  selectedNativeTid.value = nativeThreads.value.find(thread => thread.mainThread)?.systemTid ?? nativeThreads.value[0]?.systemTid ?? null
  selectedBuffer.value = 'main'
}, { immediate: true })

/** 用中文解释分区状态，缺失、失败与截断互不混淆。 */
function partitionText(partition: DiagnosticPartition | null | undefined): string {
  if (!partition) return '未采集'
  const labels = { ok: '采集成功', partial: '部分采集', unavailable: '不可用', skipped: '已跳过' }
  const reason = partition.reason ? ` · ${reasonText(partition.reason)}` : ''
  const truncated = partition.truncated ? ' · 已截断' : ''
  return `${labels[partition.status]}${reason}${truncated}`
}

/** 将安全原因类别转换为简短中文说明。 */
function reasonText(reason: string | null | undefined): string {
  const labels: Record<string, string> = {
    permission_denied: '权限受限', unsupported: '系统不支持', timeout: '采集超时', io_error: '读取失败',
    memory_pressure: '内存不足', interrupted: '采集被中断', size_limit: '大小受限', count_limit: '数量受限',
    parser_busy: '还原资源繁忙', mapping_unavailable: 'mapping 不可用', invalid_input: '还原输入无效',
    invalid_stack: '堆栈无效', empty_output: '还原结果为空', output_limit: '还原结果超限',
    retrace_failed: '还原失败', budget_exhausted: '还原时间不足',
  }
  return reason ? (labels[reason] ?? '处理失败') : '无'
}

/** 明确展示每个线程自己的还原状态。 */
function threadStatus(status: string): string {
  const labels: Record<string, string> = { pending: '等待还原', failed: '还原失败 · 保留原始帧', symbolicated: '已还原', not_applicable: '无帧 · 不适用还原' }
  return labels[status] ?? '状态未知'
}

/** 将可选字节数同时显示为人类可读单位与精确字节数。 */
function bytes(value: unknown): string {
  if (typeof value !== 'number') return '—'
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KiB（${value.toLocaleString('zh-CN')} B）`
  return `${(value / (1024 * 1024)).toFixed(1)} MiB（${value.toLocaleString('zh-CN')} B）`
}

/** 采集时间是 Unix 毫秒，无法表示的值不伪造日期。 */
function timestamp(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : formatDateTime(date.toISOString())
}

/** 将 Java 帧格式化为文本，不生成缺失的调用帧。 */
function frameText(frame: StackFrame): string {
  const file = frame.fileName ?? '未知文件'
  const line = frame.lineNumber !== null && frame.lineNumber !== undefined && frame.lineNumber >= 0 ? String(frame.lineNumber) : '未知行号'
  return `${frame.className}.${frame.methodName}（${file}:${line}）`
}
</script>

<template>
  <section class="panel detail-card diagnostics" aria-label="崩溃诊断现场">
    <div class="panel-header diagnostics-header">
      <div><h2>崩溃诊断现场</h2><p>线程为崩溃处理期间的近似快照；日志和资源信息受设备与权限影响。</p></div>
    </div>
    <p v-if="!diagnostics" class="diagnostic-empty">此事件未采集诊断上下文。</p>
    <template v-else>
      <div class="diagnostic-section">
        <div class="diagnostic-heading"><h3>崩溃线程</h3><span>{{ partitionText(diagnostics.crashedThread) }}</span></div>
        <dl v-if="diagnostics.crashedThread" class="diagnostic-grid">
          <div><dt>线程名</dt><dd>{{ diagnostics.crashedThread.name ?? '—' }}</dd></div>
          <div><dt>Java 线程 ID</dt><dd>{{ diagnostics.crashedThread.javaId ?? '—' }}</dd></div>
          <div><dt>系统 TID</dt><dd>{{ diagnostics.crashedThread.systemTid ?? '—' }}</dd></div>
          <div><dt>状态 / 优先级</dt><dd>{{ diagnostics.crashedThread.state ?? '—' }} / {{ diagnostics.crashedThread.priority ?? '—' }}</dd></div>
          <div><dt>主线程</dt><dd>{{ diagnostics.crashedThread.mainThread == null ? '未知' : diagnostics.crashedThread.mainThread ? '是' : '否' }}</dd></div>
          <div><dt>守护线程</dt><dd>{{ diagnostics.crashedThread.daemon == null ? '未知' : diagnostics.crashedThread.daemon ? '是' : '否' }}</dd></div>
        </dl>
      </div>

      <div class="diagnostic-section">
        <div class="diagnostic-heading"><h3>{{ nativeCrash ? 'Java 线程快照' : '其他线程' }}</h3><span>{{ partitionText(diagnostics.otherThreads) }}</span></div>
        <p v-if="diagnostics.otherThreads" class="diagnostic-hint">观测总数 {{ diagnostics.otherThreads.observedCount ?? '未知' }} · 已保存 {{ threads.length }} · 遗漏 {{ diagnostics.otherThreads.omittedCount ?? '未知' }}</p>
        <p v-if="!threads.length" class="diagnostic-empty">{{ diagnostics.otherThreads?.status === 'ok' ? '采集成功，没有其他线程快照。' : '没有可展示的其他线程快照。' }}</p>
        <div v-else class="thread-layout">
          <div class="thread-list" role="tablist" aria-label="其他线程">
            <button v-for="thread in threads" :key="thread.javaId" type="button" role="tab"
              :aria-selected="thread.javaId === selectedThreadId" :class="{ active: thread.javaId === selectedThreadId }"
              @click="selectedThreadId = thread.javaId">
              <strong>{{ thread.name || '未命名线程' }}</strong><span>Java ID {{ thread.javaId }}<template v-if="thread.mainThread"> · 主线程</template></span>
              <small>{{ nativeCrash && thread.symbolicationStatus === 'pending' ? '原始 Java 帧' : threadStatus(thread.symbolicationStatus) }}</small>
            </button>
          </div>
          <div v-if="selectedThread" class="thread-detail" role="tabpanel">
            <div class="thread-title"><strong>{{ selectedThread.name || '未命名线程' }}</strong><span>Java ID {{ selectedThread.javaId }}<template v-if="selectedThread.mainThread"> · 主线程</template></span></div>
            <p v-if="nativeCrash" class="diagnostic-hint">系统 TID {{ selectedThread.systemTid ?? '未知' }} · {{ selectedThread.relationToCrash === 'other' ? '已确认不是崩溃线程' : '与崩溃线程的关系未知' }}</p>
            <p class="diagnostic-hint">{{ selectedThread.state }} · 优先级 {{ selectedThread.priority }} · {{ selectedThread.daemon ? '守护线程' : '非守护线程' }}<template v-if="selectedThread.truncated"> · 堆栈已截断</template></p>
            <p class="diagnostic-hint">{{ nativeCrash && selectedThread.symbolicationStatus === 'pending' ? '原始 Java 帧；本期未提供还原' : threadStatus(selectedThread.symbolicationStatus) }}<template v-if="selectedThread.symbolicationReason && !nativeCrash"> · {{ reasonText(selectedThread.symbolicationReason) }}</template></p>
            <p v-if="selectedThread.symbolicationStatus === 'symbolicated'" class="diagnostic-hint">mapping {{ selectedThread.symbolFileId }} · revision {{ selectedThread.symbolFileRevision }} · SHA-256 {{ selectedThread.symbolFileSha256 }}</p>
            <pre v-if="selectedThread.stack.format === 'retrace_text'" class="diagnostic-pre">{{ selectedThread.stack.content }}</pre>
            <p v-else-if="selectedThread.stack.content.length === 0" class="diagnostic-empty">没有可取得的 Java 堆栈帧。</p>
            <ol v-else class="diagnostic-frames"><li v-for="(frame, index) in selectedThread.stack.content" :key="index"><code>{{ frameText(frame) }}</code></li></ol>
          </div>
        </div>
      </div>

      <div v-if="nativeCrash" class="diagnostic-section">
        <div class="diagnostic-heading"><h3>其他 Native 线程</h3><span>{{ partitionText(diagnostics.nativeThreads) }}</span></div>
        <p v-if="diagnostics.nativeThreads" class="diagnostic-hint">观测总数 {{ diagnostics.nativeThreads.observedCount ?? '未知' }} · 已保存 {{ nativeThreads.length }} · 遗漏 {{ diagnostics.nativeThreads.omittedCount ?? '未知' }}</p>
        <p v-if="!nativeThreads.length" class="diagnostic-empty">没有可展示的其他 Native 线程快照。</p>
        <div v-else class="thread-layout">
          <div class="thread-list" role="tablist" aria-label="其他 Native 线程">
            <button v-for="thread in nativeThreads" :key="thread.systemTid" type="button" role="tab"
              :aria-selected="thread.systemTid === selectedNativeTid" :class="{ active: thread.systemTid === selectedNativeTid }"
              @click="selectedNativeTid = thread.systemTid">
              <strong>{{ thread.name || '未命名线程' }}</strong><span>系统 TID {{ thread.systemTid }}<template v-if="thread.mainThread"> · 主线程</template></span>
              <small>{{ partitionText(thread.backtrace) }}</small>
            </button>
          </div>
          <div v-if="selectedNativeThread" class="thread-detail" role="tabpanel">
            <div class="thread-title"><strong>{{ selectedNativeThread.name || '未命名线程' }}</strong><span>系统 TID {{ selectedNativeThread.systemTid }}</span></div>
            <p class="diagnostic-hint">{{ partitionText(selectedNativeThread.backtrace) }}</p>
            <NativeFrameList :frames="selectedNativeThread.backtrace.frames" :modules="nativeCrash.modules" />
          </div>
        </div>
      </div>

      <div class="diagnostic-section">
        <div class="diagnostic-heading"><h3>Logcat</h3><span>{{ partitionText(diagnostics.logcat) }}</span></div>
        <template v-if="diagnostics.logcat">
          <div class="segmented-control" role="tablist" aria-label="日志缓冲区">
            <button v-for="buffer in buffers" :key="buffer.key" type="button" role="tab" :aria-selected="selectedBuffer === buffer.key"
              :class="{ active: selectedBuffer === buffer.key }" @click="selectedBuffer = buffer.key">{{ buffer.label }}</button>
          </div>
          <p class="diagnostic-hint">{{ partitionText(logBuffer) }}<template v-if="logBuffer?.capturedAt != null"> · 采集于 {{ timestamp(logBuffer.capturedAt) }}</template></p>
          <pre v-if="logBuffer?.lines.length" class="diagnostic-pre">{{ logBuffer.lines.join('\n') }}</pre>
          <p v-else class="diagnostic-empty">{{ logBuffer?.status === 'ok' ? '采集成功，日志为空。' : '没有可展示的日志。' }}</p>
        </template>
        <p v-else class="diagnostic-empty">未采集日志。</p>
      </div>

      <div class="diagnostic-section">
        <div class="diagnostic-heading"><h3>内存快照</h3><span>{{ partitionText(diagnostics.memory) }}</span></div>
        <template v-if="diagnostics.memory">
          <dl class="diagnostic-grid memory-grid"><div v-for="metric in memoryMetrics" :key="metric.key"><dt>{{ metric.label }}</dt><dd>{{ bytes(diagnostics.memory[metric.key]) }}</dd></div></dl>
          <div class="diagnostic-details"><details v-for="item in memoryTexts" :key="item.key"><summary>{{ item.label }}</summary><pre v-if="diagnostics.memory[item.key]" class="diagnostic-pre">{{ diagnostics.memory[item.key] }}</pre><p v-else class="diagnostic-empty">{{ diagnostics.memory[item.key] === '' ? '内容为空。' : '未取得原文。' }}</p></details></div>
        </template>
        <p v-else class="diagnostic-empty">未采集内存信息。</p>
      </div>

      <div class="diagnostic-section">
        <div class="diagnostic-heading"><h3>文件描述符</h3><span>{{ partitionText(diagnostics.openFiles) }}</span></div>
        <template v-if="diagnostics.openFiles">
          <p class="diagnostic-hint">总数 {{ diagnostics.openFiles.count ?? '未知' }} · 已保存 {{ diagnostics.openFiles.entries.length }} 条</p>
          <ul v-if="diagnostics.openFiles.entries.length" class="fd-list"><li v-for="entry in diagnostics.openFiles.entries" :key="entry.fd"><code>{{ entry.fd }}</code><span>{{ entry.target ?? '目标未取得' }}</span></li></ul>
          <p v-else class="diagnostic-empty">{{ diagnostics.openFiles.status === 'ok' ? '采集成功，没有文件描述符明细。' : '没有可展示的文件描述符明细。' }}</p>
        </template>
        <p v-else class="diagnostic-empty">未采集文件描述符。</p>
      </div>

      <div class="diagnostic-section">
        <div class="diagnostic-heading"><h3>进程与设备</h3><span>进程：{{ partitionText(diagnostics.process) }} · 设备：{{ partitionText(diagnostics.device) }}</span></div>
        <dl class="diagnostic-grid">
          <div><dt>进程 PID</dt><dd>{{ diagnostics.process?.pid ?? '—' }}</dd></div>
          <div><dt>进程名</dt><dd>{{ diagnostics.process?.name ?? '—' }}</dd></div>
          <div><dt>前后台</dt><dd>{{ diagnostics.process?.foreground == null ? '未知' : diagnostics.process.foreground ? '前台' : '后台' }}</dd></div>
          <div><dt>进程启动时间</dt><dd>{{ timestamp(diagnostics.process?.startedAt) }}</dd></div>
          <div><dt>SDK 初始化时间</dt><dd>{{ timestamp(diagnostics.process?.sdkInitializedAt) }}</dd></div>
          <div><dt>Android API</dt><dd>{{ diagnostics.device?.apiLevel ?? '—' }}</dd></div>
          <div><dt>ABI</dt><dd>{{ diagnostics.device?.abis.length ? diagnostics.device.abis.join(' / ') : '—' }}</dd></div>
          <div><dt>厂商 / 品牌</dt><dd>{{ diagnostics.device?.manufacturer ?? '—' }} / {{ diagnostics.device?.brand ?? '—' }}</dd></div>
          <div class="diagnostic-wide"><dt>系统构建 fingerprint</dt><dd>{{ diagnostics.device?.buildFingerprint ?? '—' }}</dd></div>
        </dl>
      </div>
    </template>
  </section>
</template>

<style scoped>
.diagnostics { margin-top: 20px; }
.diagnostics-header { padding: 0 0 16px; }
.diagnostic-section { padding: 20px 0; border-top: 1px solid var(--line); }
.diagnostic-section:last-child { padding-bottom: 0; }
.diagnostic-heading { display: flex; flex-wrap: wrap; align-items: baseline; justify-content: space-between; gap: 8px 16px; margin-bottom: 12px; }
.diagnostic-heading h3 { margin: 0; font-size: 14px; }
.diagnostic-heading span, .diagnostic-hint, .diagnostic-empty { color: var(--muted); font-size: 12px; }
.diagnostic-hint { margin: 8px 0; overflow-wrap: anywhere; }
.diagnostic-empty { margin: 8px 0; }
.diagnostic-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; margin: 0; }
.diagnostic-grid dt { margin-bottom: 5px; color: var(--muted); font-size: 11px; }
.diagnostic-grid dd { margin: 0; overflow-wrap: anywhere; font-size: 12px; }
.diagnostic-wide { grid-column: span 2; }
.thread-layout { display: grid; grid-template-columns: minmax(180px, 250px) minmax(0, 1fr); gap: 16px; }
.thread-list { display: grid; align-content: start; gap: 6px; max-height: 370px; overflow: auto; }
.thread-list button { display: grid; gap: 4px; padding: 10px; border: 1px solid var(--line); border-radius: 8px; background: transparent; color: inherit; text-align: left; overflow-wrap: anywhere; }
.thread-list button.active { border-color: var(--primary); background: var(--surface-muted); }
.thread-list button span, .thread-list button small, .thread-title span { color: var(--muted); font-size: 11px; }
.thread-list button:focus-visible { outline: 3px solid rgba(39, 104, 199, .22); outline-offset: 2px; }
.thread-detail { min-width: 0; }
.thread-title { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 8px; font-size: 13px; }
.diagnostic-pre { max-height: 380px; overflow: auto; margin: 10px 0 0; padding: 14px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface-muted); font: 12px/1.6 "SFMono-Regular", Consolas, monospace; white-space: pre-wrap; overflow-wrap: anywhere; }
.diagnostic-frames { max-height: 380px; overflow: auto; margin: 10px 0 0; padding: 10px 10px 10px 36px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface-muted); font-size: 12px; }
.diagnostic-frames li { padding: 4px; overflow-wrap: anywhere; }
.diagnostic-details { display: grid; gap: 8px; margin-top: 16px; }
.diagnostic-details summary { cursor: pointer; color: var(--primary); font-size: 12px; }
.fd-list { display: grid; gap: 6px; max-height: 220px; overflow: auto; margin: 0; padding: 0; list-style: none; }
.fd-list li { display: flex; gap: 14px; font-size: 12px; }
@media (max-width: 800px) { .diagnostic-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .thread-layout { grid-template-columns: 1fr; } }
</style>
