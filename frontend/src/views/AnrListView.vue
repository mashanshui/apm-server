<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { anrApi } from '../api/anrApi'
import AppLayout from '../components/AppLayout.vue'
import StatCard from '../components/StatCard.vue'
import StatusMessage from '../components/StatusMessage.vue'
import { createJankCursorQuery, createJankQueryRegion } from '../composables/useJankQuery'
import { useSessionStore } from '../stores/session'
import type { AnrEventSummary, AnrFilters, AnrOverview } from '../types/anr'
import { anrFiltersToQuery, anrSourceLabel, parseAnrFilters } from '../utils/anrQuery'
import { formatDateTime, formatNumber, fromDateTimeLocal, toDateTimeLocal } from '../utils/format'

/** 路由提供应用与可分享的筛选条件。 */
const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const appId = computed(() => String(route.params.appId))
const filters = ref<AnrFilters>(parseAnrFilters(route.query))
/** 概览与列表独立处理加载和失败。 */
const overview = createJankQueryRegion<AnrOverview>()
const events = createJankCursorQuery<AnrEventSummary>(event => event.eventId)
const draft = reactive({ from: '', to: '', appVersion: '', source: '' as AnrFilters['source'] })
const formError = ref('')
const authInvalidated = ref(false)

/** 复制路由筛选到草稿，避免输入时立即请求。 */
function syncDraft(value: AnrFilters): void {
  draft.from = toDateTimeLocal(value.from)
  draft.to = toDateTimeLocal(value.to)
  draft.appVersion = value.appVersion
  draft.source = value.source
}

/** 同一时间窗并发读取概览和第一页，旧请求由查询区域取消。 */
async function load(): Promise<void> {
  if (authInvalidated.value) return
  const current = { ...filters.value }
  const currentApp = appId.value
  overview.reset()
  await Promise.all([
    overview.run(signal => anrApi.overview(currentApp, current, signal)),
    events.load(async (_, signal) => {
      const page = await anrApi.events(currentApp, current, undefined, signal)
      return { items: page.events, nextCursor: page.nextCursor, from: page.from, to: page.to }
    }),
  ])
  console.info('[AnrListView] ANR 查询已完成')
}

/** 续页沿用初始时间窗口及不透明游标。 */
async function loadMore(): Promise<void> {
  const current = { ...filters.value, ...(events.range.value ?? {}) }
  const currentApp = appId.value
  await events.loadMore(async (cursor, signal) => {
    const page = await anrApi.events(currentApp, current, cursor, signal)
    return { items: page.events, nextCursor: page.nextCursor, from: page.from, to: page.to }
  })
}

/** 检查时间范围后更新 URL，筛选变化自动清除旧游标。 */
function applyFilters(): void {
  const from = fromDateTimeLocal(draft.from)
  const to = fromDateTimeLocal(draft.to)
  const span = Date.parse(to) - Date.parse(from)
  if (!Number.isFinite(span) || span <= 0 || span > 30 * 24 * 60 * 60 * 1000) {
    formError.value = '请选择不超过 30 天且结束时间晚于开始时间的范围。'
    return
  }
  formError.value = ''
  void router.push({ name: 'anr-list', params: { appId: appId.value }, query: anrFiltersToQuery({
    from, to, appVersion: draft.appVersion.trim(), source: draft.source,
  }) })
}

/** 会话失效时立刻清除已显示的数据。 */
function invalidate(): void {
  authInvalidated.value = true
  overview.reset()
  events.reset()
}

watch(() => route.fullPath, () => {
  filters.value = parseAnrFilters(route.query)
  syncDraft(filters.value)
  void load()
}, { immediate: true })
window.addEventListener('apm:auth-expired', invalidate)
watch(() => session.isAuthenticated, (authenticated, previous) => {
  if (!authenticated && previous) invalidate()
  else if (authenticated && !previous && authInvalidated.value) { authInvalidated.value = false; void load() }
}, { flush: 'sync' })
onBeforeUnmount(() => {
  window.removeEventListener('apm:auth-expired', invalidate)
  overview.cancel()
  events.cancel()
})
</script>

<template>
  <AppLayout :app-id="appId">
    <header class="page-header">
      <div><p class="eyebrow">STABILITY / ANR</p><h1>ANR 事件</h1><p class="subtitle">查看已确认的应用无响应事件及原进程诊断现场。</p></div>
    </header>
    <form class="panel filter-panel" @submit.prevent="applyFilters">
      <div class="filter-grid">
        <div class="field"><label for="anr-from">开始时间</label><input id="anr-from" v-model="draft.from" type="datetime-local" required /></div>
        <div class="field"><label for="anr-to">结束时间</label><input id="anr-to" v-model="draft.to" type="datetime-local" required /></div>
        <div class="field"><label for="anr-version">应用版本</label><input id="anr-version" v-model="draft.appVersion" placeholder="例如 3.2.0" /></div>
        <div class="field"><label for="anr-source">判定来源</label><select id="anr-source" v-model="draft.source"><option value="">全部来源</option><option value="message_timeout">主线程消息超时</option><option value="process_error_state">系统进程错误状态</option></select></div>
      </div>
      <div class="filter-actions"><span class="subtitle">默认最近 24 小时，最长 30 天</span><button class="button button-primary" type="submit">应用筛选</button></div>
      <p v-if="formError" class="notice notice-warning" role="alert">{{ formError }}</p>
    </form>
    <div v-if="authInvalidated" class="notice notice-warning" role="status">登录状态已失效，请重新登录。</div>
    <template v-else>
      <StatusMessage v-if="overview.error.value" kind="error" :message="overview.error.value" @retry="load" />
      <div v-else class="stats-grid jank-stats-grid anr-stats">
        <StatCard label="发生次数" :value="overview.loading.value ? '—' : formatNumber(overview.data.value?.eventCount)" footnote="已接收的逻辑事件" tone="warning" />
        <StatCard label="影响设备" :value="overview.loading.value ? '—' : formatNumber(overview.data.value?.deviceCount)" footnote="筛选范围内去重设备" />
      </div>
      <section class="panel">
        <div class="panel-header"><div><h2>事件列表</h2><p>按发生时间倒序展示；详情保留原始现场。</p></div><span class="badge">已加载 {{ events.items.value.length }} 条</span></div>
        <StatusMessage v-if="events.error.value" kind="error" :message="events.error.value" @retry="load" />
        <StatusMessage v-else-if="events.loading.value" kind="loading" message="正在加载 ANR 事件…" />
        <StatusMessage v-else-if="!events.items.value.length" kind="empty" message="当前筛选范围没有 ANR 事件" />
        <template v-else>
          <div class="table-wrap"><table class="data-table"><thead><tr><th>发生时间</th><th>事件 ID</th><th>判定来源</th><th>应用版本</th><th>设备型号</th></tr></thead>
            <tbody><tr v-for="event in events.items.value" :key="event.eventId"><td>{{ formatDateTime(event.occurredAt) }}</td><td><RouterLink class="link-button fingerprint" :to="{ name: 'anr-event-detail', params: { appId, eventId: event.eventId }, query: anrFiltersToQuery(filters) }">{{ event.eventId }}</RouterLink></td><td>{{ anrSourceLabel(event.source) }}</td><td>{{ event.appVersion }} / {{ event.versionCode ?? '—' }}</td><td>{{ event.deviceModel || '—' }}</td></tr></tbody>
          </table></div>
          <div v-if="events.appendError.value" class="notice" role="alert">加载更多失败：{{ events.appendError.value }} <button class="link-button" type="button" @click="events.cursorInvalid.value ? load() : loadMore()">重试</button></div>
          <div class="pagination-bar"><span>{{ events.nextCursor.value ? '可继续加载更多事件' : '已显示全部事件' }}</span><button class="button" type="button" :disabled="!events.nextCursor.value || events.loadingMore.value || events.cursorInvalid.value" @click="loadMore">{{ events.loadingMore.value ? '加载中…' : '加载更多' }}</button></div>
        </template>
      </section>
    </template>
  </AppLayout>
</template>

<style scoped>
.anr-stats { grid-template-columns: repeat(2, minmax(0, 220px)); }
</style>
