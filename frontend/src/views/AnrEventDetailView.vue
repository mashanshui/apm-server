<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { anrApi } from '../api/anrApi'
import { errorMessage, isAbortError } from '../api/http'
import AnrStackPanel from '../components/AnrStackPanel.vue'
import AppLayout from '../components/AppLayout.vue'
import CrashDiagnosticsPanel from '../components/CrashDiagnosticsPanel.vue'
import EventIdentityFields from '../components/EventIdentityFields.vue'
import StatusMessage from '../components/StatusMessage.vue'
import { useSessionStore } from '../stores/session'
import type { AnrEventDetail, AnrTextPartition } from '../types/anr'
import { anrSourceLabel } from '../utils/anrQuery'
import { formatDateTime } from '../utils/format'

/** 应用和事件身份始终由路由提供。 */
const route = useRoute()
const session = useSessionStore()
const appId = computed(() => String(route.params.appId))
const eventId = computed(() => String(route.params.eventId))
const event = ref<AnrEventDetail | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
const authInvalidated = ref(false)
const copyStatus = ref('')
let token = 0
let controller: AbortController | null = null

/** 路由变化后取消旧请求，并禁止迟到结果覆盖当前事件。 */
async function load(): Promise<void> {
  if (authInvalidated.value) return
  const requestToken = ++token
  controller?.abort()
  controller = new AbortController()
  event.value = null
  error.value = null
  copyStatus.value = ''
  loading.value = true
  try {
    const result = await anrApi.event(appId.value, eventId.value, controller.signal)
    if (requestToken === token) {
      event.value = result
      console.info('[AnrEventDetailView] ANR 详情已加载')
    }
  } catch (failure) {
    if (requestToken === token && !isAbortError(failure)) {
      error.value = errorMessage(failure)
      console.warn('[AnrEventDetailView] ANR 详情加载失败')
    }
  } finally {
    if (requestToken === token) loading.value = false
  }
}

/** 明确区分缺失、失败与截断。 */
function partitionText(part: AnrTextPartition | null | undefined): string {
  if (!part) return '未采集'
  const labels: Record<string, string> = { ok: '采集成功', partial: '部分采集', unavailable: '不可用', skipped: '已跳过' }
  const reasons: Record<string, string> = { permission_denied: '权限受限', timeout: '采集超时', io_error: '读取失败',
    interrupted: '采集被中断', not_found: '系统未提供', ambiguous: '归属不明确', size_limit: '大小受限', unsupported: '系统不支持' }
  return `${labels[part.status] ?? '状态未知'}${part.reason ? ` · ${reasons[part.reason] ?? part.reason}` : ''}${part.truncated ? ' · 已截断' : ''}`
}

/** 显示有效的原进程采集时间。 */
function captureTime(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : formatDateTime(date.toISOString())
}

/** 复制完整事件 ID 并反馈浏览器权限失败。 */
async function copyEventId(): Promise<void> {
  try {
    await navigator.clipboard.writeText(eventId.value)
    copyStatus.value = '已复制事件 ID。'
  } catch {
    copyStatus.value = '复制事件 ID 失败，请检查浏览器剪贴板权限。'
  }
}

/** 会话失效后立即清空原始现场。 */
function invalidate(): void {
  authInvalidated.value = true
  token++
  controller?.abort()
  event.value = null
  loading.value = false
  error.value = null
}

watch(() => route.fullPath, () => { void load() }, { immediate: true })
window.addEventListener('apm:auth-expired', invalidate)
watch(() => session.isAuthenticated, (authenticated, previous) => {
  if (!authenticated && previous) invalidate()
  else if (authenticated && !previous && authInvalidated.value) { authInvalidated.value = false; void load() }
}, { flush: 'sync' })
onBeforeUnmount(() => {
  window.removeEventListener('apm:auth-expired', invalidate)
  token++
  controller?.abort()
})
</script>

<template>
  <AppLayout :app-id="appId">
    <div class="breadcrumb"><RouterLink :to="{ name: 'anr-list', params: { appId }, query: route.query }">ANR</RouterLink><span class="breadcrumb-separator">/</span><span>事件详情</span></div>
    <header class="page-header"><div><p class="eyebrow">ANR EVENT</p><h1>ANR 事件详情</h1><p class="subtitle">事件 ID <span class="fingerprint">{{ eventId }}</span> <button class="identity-copy-button" type="button" @click="copyEventId">复制</button></p><p v-if="copyStatus" role="status">{{ copyStatus }}</p></div><RouterLink class="button" :to="{ name: 'anr-list', params: { appId }, query: route.query }">返回列表</RouterLink></header>
    <div v-if="authInvalidated" class="notice notice-warning" role="status">登录状态已失效，请重新登录。</div>
    <StatusMessage v-else-if="error" kind="error" :message="error" @retry="load" />
    <StatusMessage v-else-if="loading" kind="loading" message="正在加载 ANR 详情…" />
    <template v-else-if="event">
      <section class="panel detail-card"><div class="panel-header"><div><h2>事件信息</h2><p>发生时间和身份来自原进程，诊断分区按实际采集状态展示。</p></div><span class="badge">{{ anrSourceLabel(event.anr.source) }}</span></div>
        <dl class="meta-grid"><div class="meta-item"><dt>发生时间</dt><dd>{{ formatDateTime(event.metadata.occurredAt) }}</dd></div><div class="meta-item"><dt>应用版本 / 构建</dt><dd>{{ event.metadata.appVersion }} / {{ event.metadata.buildId }}</dd></div><div class="meta-item"><dt>设备 / 系统</dt><dd>{{ event.metadata.deviceModel }} / Android {{ event.metadata.osVersion }}</dd></div><div class="meta-item"><dt>前后台</dt><dd>{{ event.anr.foreground == null ? '未知' : event.anr.foreground ? '前台' : '后台' }}</dd></div><div class="meta-item"><dt>消息时间差</dt><dd>{{ event.anr.messageWhenMillis == null ? '—' : `${event.anr.messageWhenMillis} ms` }}</dd></div><div class="meta-item"><dt>队首消息</dt><dd>{{ event.anr.message ?? '—' }}</dd></div></dl>
        <EventIdentityFields :key="event.metadata.eventId" :anonymous-device-id="event.metadata.anonymousDeviceId" :session-id="event.metadata.sessionId" :process-id="event.metadata.processId" />
      </section>
      <AnrStackPanel :key="`${event.metadata.eventId}-main`" title="主线程 Java 栈" :event-id="event.metadata.eventId" :raw="event.anr.mainThreadStack" :truncated="event.anr.mainThreadStackTruncated" :view="event.mainThreadJava" />
      <AnrStackPanel :key="`${event.metadata.eventId}-trace`" title="系统 trace（采集片段）" :event-id="event.metadata.eventId" :raw="event.anr.trace?.text" :truncated="event.anr.trace?.truncated" :part="event.anr.trace" :view="event.traceJava" trace />
      <section class="panel detail-card anr-info"><div class="panel-header"><div><h2>ANR_INFO</h2><p>系统进程错误状态提供的详细说明。<template v-if="event.anr.anrInfo?.capturedAt != null"> 采集于 {{ captureTime(event.anr.anrInfo.capturedAt) }}。</template></p></div><span class="badge">{{ partitionText(event.anr.anrInfo) }}</span></div><pre v-if="event.anr.anrInfo?.text" class="anr-pre">{{ event.anr.anrInfo.text }}</pre><p v-else class="subtitle">未取得可展示的系统说明。</p></section>
      <CrashDiagnosticsPanel variant="anr" :diagnostics="event.anr.diagnostics ?? null" />
    </template>
  </AppLayout>
</template>

<style scoped>
.anr-info { margin-top: 20px; }
.anr-pre { max-height: 360px; overflow: auto; padding: 14px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface-muted); white-space: pre-wrap; overflow-wrap: anywhere; font: 12px/1.6 "SFMono-Regular", Consolas, monospace; }
</style>
