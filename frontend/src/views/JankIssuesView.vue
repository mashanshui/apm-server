<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppLayout from '../components/AppLayout.vue'
import JankFilterBar from '../components/JankFilterBar.vue'
import JankIssueTable from '../components/JankIssueTable.vue'
import JankTrendChart from '../components/JankTrendChart.vue'
import StatCard from '../components/StatCard.vue'
import StatusMessage from '../components/StatusMessage.vue'
import { useJankIssues } from '../composables/useJankIssues'
import type { JankFilterForm, JankIssueSummary } from '../types/jank'
import { formatDateTime, formatNumber } from '../utils/format'
import { jankFiltersToQuery, parseJankFilters } from '../utils/jankQuery'

const route = useRoute()
const router = useRouter()
const appId = computed(() => String(route.params.appId))
const filters = ref<JankFilterForm>(parseJankFilters(route.query))
const query = useJankIssues(appId, filters)
const stats = computed(() => query.overview.value?.stats ?? null)

watch(() => route.fullPath, () => {
  filters.value = parseJankFilters(route.query)
  void query.load()
}, { immediate: true })
onBeforeUnmount(query.cancel)

function applyFilters(next: JankFilterForm): void {
  void router.push({ name: 'jank-issues', params: { appId: appId.value }, query: jankFiltersToQuery(next) })
}

function openIssue(issue: JankIssueSummary): void {
  void router.push({
    name: 'jank-issue-events',
    params: { appId: appId.value, fingerprint: issue.fingerprint },
    query: jankFiltersToQuery({ ...filters.value, fingerprint: issue.fingerprint }),
  })
}

function duration(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : `${formatNumber(value)} ms`
}

const overviewRange = computed(() => query.overview.value
  ? `${formatDateTime(query.overview.value.from)} — ${formatDateTime(query.overview.value.to)}`
  : '')
</script>

<template>
  <AppLayout :app-id="appId">
    <header class="page-header">
      <div><p class="eyebrow">APP / {{ appId }}</p><h1>卡顿问题分析</h1><p class="subtitle">从精确消息耗时趋势定位 Issue，再下钻到采样估算证据。</p></div>
      <div class="page-header-badges"><span v-if="query.overview.value" class="badge badge-success">数据源 · {{ query.overview.value.dataSource }}</span><span v-if="overviewRange" class="badge">{{ overviewRange }}</span></div>
    </header>

    <JankFilterBar :model-value="filters" :loading="query.loading.value" @submit="applyFilters" />

    <section class="jank-region" aria-labelledby="jank-overview-heading">
      <div class="region-heading"><div><h2 id="jank-overview-heading">范围总览</h2><p>计数和精确消息总耗时来自服务端正式契约</p></div><button v-if="query.overviewError.value" class="button" type="button" @click="query.loadOverview">重试总览</button></div>
      <StatusMessage v-if="query.overviewError.value" kind="error" :message="query.overviewError.value" @retry="query.loadOverview" />
      <StatusMessage v-else-if="query.overviewLoading.value" kind="loading" message="正在加载卡顿总览…" />
      <div v-else-if="stats" class="stats-grid jank-stats-grid">
        <StatCard label="卡顿事件" :value="formatNumber(stats.jankEvents)" :footnote="stats.status === 'no_data' ? '当前范围无数据' : '唯一事件数'" />
        <StatCard label="受影响会话" :value="formatNumber(stats.affectedSessions)" footnote="去重会话数" />
        <StatCard label="受影响设备" :value="formatNumber(stats.affectedDevices)" footnote="匿名设备去重数" />
        <StatCard label="可归组事件" :value="formatNumber(stats.groupableEvents)" footnote="具有稳定问题指纹" />
        <StatCard label="精确消息 P50" :value="duration(stats.exactMessageDuration.p50Ms)" footnote="消息开始/结束证据" />
        <StatCard label="精确消息 P90" :value="duration(stats.exactMessageDuration.p90Ms)" footnote="消息开始/结束证据" tone="warning" />
        <StatCard label="精确消息 P99" :value="duration(stats.exactMessageDuration.p99Ms)" footnote="消息开始/结束证据" tone="danger" />
      </div>
      <StatusMessage v-else kind="empty" message="当前筛选范围没有卡顿数据" />
    </section>

    <section class="panel jank-region" aria-labelledby="jank-trend-heading">
      <div class="panel-header"><div><h2 id="jank-trend-heading">卡顿趋势</h2><p>按{{ filters.interval === 'hour' ? '小时' : '天' }}展示卡顿事件与受影响设备</p></div><button v-if="query.trendError.value" class="button" type="button" @click="query.loadTrend">重试趋势</button></div>
      <StatusMessage v-if="query.trendError.value" kind="error" :message="query.trendError.value" @retry="query.loadTrend" />
      <StatusMessage v-else-if="query.trendLoading.value" kind="loading" message="正在加载卡顿趋势…" />
      <JankTrendChart v-else-if="query.trend.value?.points.length" :points="query.trend.value.points" :interval="query.trend.value.interval" />
      <StatusMessage v-else kind="empty" message="当前范围没有趋势数据" />
    </section>

    <section class="panel jank-region" aria-labelledby="jank-issues-heading">
      <div class="panel-header"><div><h2 id="jank-issues-heading">卡顿 Issue 排行</h2><p>精确消息耗时与采样估算堆栈耗时分列展示，点击指纹查看事件列表</p></div><span v-if="query.issues.value.length" class="badge">{{ query.issues.value.length }} 个问题</span></div>
      <StatusMessage v-if="query.issuesError.value" kind="error" :message="query.issuesError.value" @retry="query.loadIssues" />
      <div v-else>
        <div v-if="query.issueAppendError.value" class="notice">追加失败：{{ query.issueAppendError.value }}，已加载的问题仍然保留。 <button class="link-button" type="button" @click="query.loadMoreIssues">重试追加</button></div>
        <JankIssueTable :issues="query.issues.value" :next-cursor="query.nextCursor.value" :loading="query.issuesLoading.value" :loading-more="query.issuesLoadingMore.value" @open="openIssue" @next="query.loadMoreIssues" />
      </div>
    </section>
  </AppLayout>
</template>
