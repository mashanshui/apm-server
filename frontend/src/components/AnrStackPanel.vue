<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { AnrJavaView, AnrTextPartition } from '../types/anr'
import { formatDateTime } from '../utils/format'

/** 原文和独立的 Java 还原结果。 */
const props = defineProps<{ title: string; raw: string | null | undefined; truncated?: boolean;
  view: AnrJavaView | null | undefined; part?: AnrTextPartition | null; trace?: boolean; eventId: string }>()
/** 每个分区独立切换；更换事件后回到原文。 */
const selected = ref<'raw' | 'retrace'>('raw')
const hasRetrace = computed(() => !!props.view?.text && ['partial', 'symbolicated'].includes(props.view.status))
const visible = computed(() => selected.value === 'retrace' && hasRetrace.value ? props.view?.text : props.raw)

/** 用用户可理解的状态说明后台结果。 */
function statusText(view: AnrJavaView | null | undefined): string {
  const labels: Record<string, string> = { pending: '等待 Java 还原', failed: 'Java 还原失败', partial: '部分 Java 帧已还原',
    symbolicated: 'Java 帧已还原', not_applicable: '没有可还原的 Java 帧' }
  return view ? (labels[view.status] ?? '还原状态未知') : '还原状态不可用'
}

/** 系统 trace 的采集状态独立于符号还原状态。 */
function captureText(part: AnrTextPartition | null | undefined): string {
  if (!part) return '未采集'
  const labels: Record<string, string> = { ok: '采集成功', partial: '部分采集', unavailable: '不可用', skipped: '已跳过' }
  const reasons: Record<string, string> = { permission_denied: '权限受限', timeout: '采集超时', io_error: '读取失败',
    interrupted: '采集被中断', not_found: '系统未提供', ambiguous: '归属不明确', size_limit: '大小受限' }
  return `${labels[part.status] ?? '状态未知'}${part.reason ? ` · ${reasons[part.reason] ?? part.reason}` : ''}`
}

/** 原进程的采集时间可能缺失。 */
function captureTime(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : formatDateTime(date.toISOString())
}

watch(() => props.eventId, () => { selected.value = 'raw' })
</script>

<template>
  <section class="panel detail-card anr-stack" :aria-label="title">
    <div class="panel-header"><div><h2>{{ title }}</h2><p>{{ trace ? '这是 ART 首次命中写入的系统 trace 片段；Java 还原不改变线程、锁和 Native 行。' : 'Matrix 采集的主线程 Java 栈。' }}</p></div><span class="badge">{{ statusText(view) }}</span></div>
    <p v-if="trace" class="anr-hint">采集：{{ captureText(part) }}<template v-if="part?.capturedAt != null"> · {{ captureTime(part.capturedAt) }}</template><template v-if="part?.source"> · ART 首次写入</template></p>
    <p v-if="truncated" class="notice notice-warning">采集内容已截断。</p>
    <p v-if="view?.reason" class="anr-hint">处理原因：{{ view.reason }}</p>
    <p v-if="view?.symbolFileId" class="anr-hint">mapping {{ view.symbolFileId }} · 修订 {{ view.symbolFileRevision ?? '—' }}<template v-if="view.symbolFileSha256"> · SHA-256 {{ view.symbolFileSha256 }}</template></p>
    <div class="segmented-control" role="group" :aria-label="`${title}视图`"><button type="button" :class="{ active: selected === 'raw' }" @click="selected = 'raw'">原文</button><button type="button" :class="{ active: selected === 'retrace' }" :disabled="!hasRetrace" @click="selected = 'retrace'">Java 还原</button></div>
    <pre v-if="visible" class="anr-pre">{{ visible }}</pre><p v-else class="anr-hint">没有可展示的内容。</p>
  </section>
</template>

<style scoped>
.anr-stack { margin-top: 20px; }
.anr-hint { color: var(--muted); font-size: 12px; overflow-wrap: anywhere; }
.anr-pre { max-height: 480px; overflow: auto; margin: 14px 0 0; padding: 14px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface-muted); white-space: pre-wrap; overflow-wrap: anywhere; font: 12px/1.6 "SFMono-Regular", Consolas, monospace; }
</style>
