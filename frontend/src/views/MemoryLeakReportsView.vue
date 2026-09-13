<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppLayout from '../components/AppLayout.vue'
import MemoryLeakFilterBar from '../components/MemoryLeakFilterBar.vue'
import MemoryLeakIssueTable from '../components/MemoryLeakIssueTable.vue'
import MemoryLeakTrendChart from '../components/MemoryLeakTrendChart.vue'
import StatusMessage from '../components/StatusMessage.vue'
import { useMemoryLeakReports } from '../composables/useMemoryLeakReports'
import type { MemoryLeakFilterForm, MemoryLeakIssueSort, MemoryLeakTrendMetric } from '../types/memoryLeak'
import { formatNumber } from '../utils/format'
import { memoryLeakFiltersToQuery, normalizeMemoryLeakFilters, parseMemoryLeakFilters } from '../utils/memoryLeakQuery'

const route = useRoute()
const router = useRouter()
const appId = computed(() => String(route.params.appId))
const filters = ref<MemoryLeakFilterForm>(parseMemoryLeakFilters(route.query))
const query = useMemoryLeakReports(appId, filters)

watch(() => route.fullPath, () => {
  filters.value = parseMemoryLeakFilters(route.query)
  void query.load()
}, { immediate: true })
onBeforeUnmount(query.cancel)

function navigate(next: MemoryLeakFilterForm): void {
  const normalized = normalizeMemoryLeakFilters({ ...next, page: 1 })
  void router.push({ name: 'memory-leaks', params: { appId: appId.value }, query: memoryLeakFiltersToQuery(normalized) })
}

function updateQuery(partial: Partial<MemoryLeakFilterForm>): void {
  navigate({ ...filters.value, ...partial })
}

function switchTrendMetric(trendMetric: MemoryLeakTrendMetric): void { updateQuery({ trendMetric }) }
function previousPage(): void { if (filters.value.page > 1) updateQuery({ page: filters.value.page - 1 }) }
function nextPage(): void {
  const total = query.issues.value?.total ?? 0
  const pageCount = Math.max(1, Math.ceil(total / filters.value.pageSize))
  if (filters.value.page < pageCount) updateQuery({ page: filters.value.page + 1 })
}
function sortBy(sort: MemoryLeakIssueSort): void {
  updateQuery({ sort, order: filters.value.sort === sort && filters.value.order === 'desc' ? 'asc' : 'desc' })
}

const trendPoints = computed(() => query.trend.value?.points ?? [])
const trendHasData = computed(() => query.trend.value?.status !== 'no_data' && trendPoints.value.length > 0)
const issueCount = computed(() => query.issues.value?.total ?? 0)
</script>

<template>
  <AppLayout :app-id="appId">
    <header class="page-header">
      <div><p class="eyebrow">APP / {{ appId }}</p><h1>Java 内存泄漏</h1><p class="subtitle">按 SDK 报告聚合疑似问题，查看发生趋势和引用链。</p></div>
      <div class="page-header-badges"><span class="badge badge-warning">SDK 报告的疑似问题</span><span v-if="query.issues.value" class="badge">数据源 · {{ query.issues.value.dataSource }}</span></div>
    </header>

    <MemoryLeakFilterBar :model-value="filters" :loading="query.loading.value" @submit="navigate" />

    <section class="panel jank-region memory-leak-trend-region" aria-labelledby="memory-leak-trend-heading">
      <div class="panel-header"><div><h2 id="memory-leak-trend-heading">问题趋势</h2><p>按 UTC {{ filters.interval === '5m' ? '5 分钟' : filters.interval === 'hour' ? '小时' : '天' }}分桶，空桶显示为 0</p></div><div class="segmented-control" role="group" aria-label="趋势指标"><button type="button" :class="{ active: filters.trendMetric === 'occurrences' }" @click="switchTrendMetric('occurrences')">发生次数</button><button type="button" :class="{ active: filters.trendMetric === 'affectedDevices' }" @click="switchTrendMetric('affectedDevices')">影响设备数</button></div></div>
      <StatusMessage v-if="query.trendError.value" kind="error" :message="query.trendError.value" @retry="query.loadTrend" />
      <StatusMessage v-else-if="query.trendLoading.value" kind="loading" message="正在加载泄漏趋势…" />
      <MemoryLeakTrendChart v-else-if="trendHasData" :points="trendPoints" :metric="filters.trendMetric" />
      <StatusMessage v-else kind="empty" message="当前筛选范围没有趋势数据" />
    </section>

    <section class="panel jank-region memory-leak-issues-region" aria-labelledby="memory-leak-issues-heading">
      <div class="panel-header"><div><h2 id="memory-leak-issues-heading">疑似问题</h2><p>发生次数和影响设备数均由服务端去重计算，分页不会改变占比的分母。</p></div><span class="badge">{{ formatNumber(issueCount) }} 个问题</span></div>
      <div class="memory-leak-summary"><span>总发生次数：{{ formatNumber(query.issues.value?.totalOccurrences) }}</span><span>总影响设备：{{ formatNumber(query.issues.value?.totalAffectedDevices) }}</span></div>
      <StatusMessage v-if="query.issuesError.value" kind="error" :message="query.issuesError.value" @retry="query.loadIssues" />
      <MemoryLeakIssueTable v-else :issues="query.issues.value?.items ?? []" :total="query.issues.value?.total ?? 0" :page="filters.page" :page-size="filters.pageSize" :loading="query.issuesLoading.value" @previous="previousPage" @next="nextPage" @sort="sortBy" />
    </section>
  </AppLayout>
</template>
