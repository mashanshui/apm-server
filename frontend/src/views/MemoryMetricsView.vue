<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppLayout from '../components/AppLayout.vue'
import MemoryMetricFilterBar from '../components/MemoryMetricFilterBar.vue'
import MemoryMetricSummary from '../components/MemoryMetricSummary.vue'
import MemoryMetricTrendChart from '../components/MemoryMetricTrendChart.vue'
import StatusMessage from '../components/StatusMessage.vue'
import { useMemoryMetrics } from '../composables/useMemoryMetrics'
import type { MemoryMetric, MemoryMetricFilterForm, MemoryPercentile } from '../types/memory'
import { memoryMetricFiltersToQuery, memoryMetricQueryWarning, normalizeMemoryMetricFilters, parseMemoryMetricFilters } from '../utils/memoryQuery'

const route = useRoute()
const router = useRouter()
const appId = computed(() => String(route.params.appId))
const filters = ref<MemoryMetricFilterForm>(parseMemoryMetricFilters(route.query))
const query = useMemoryMetrics(appId, filters)
const metrics: { value: MemoryMetric; label: string; description: string }[] = [
  { value: 'pss', label: 'PSS', description: '进程实际占用的物理内存' },
  { value: 'vss', label: 'VSS', description: '进程虚拟地址空间' },
  { value: 'java_heap', label: 'Java 堆', description: 'totalMemory 减 freeMemory' },
]

let previousDataQueryKey: string | null = null
watch(() => route.fullPath, () => {
  const next = parseMemoryMetricFilters(route.query)
  const dataQueryKey = JSON.stringify({ ...next, percentile: undefined })
  filters.value = next
  if (previousDataQueryKey !== dataQueryKey) {
    previousDataQueryKey = dataQueryKey
    void query.load()
  }
}, { immediate: true })
onBeforeUnmount(query.cancel)

const selectedStats = computed(() => query.summary.value?.[filters.value.metric === 'java_heap' ? 'javaHeap' : filters.value.metric])
const hasTrendSamples = computed(() => query.trend.value?.points.some((point) => point.sampleCount > 0) ?? false)
const queryWarning = computed(() => memoryMetricQueryWarning(route.query))

function navigate(next: MemoryMetricFilterForm): void {
  const normalized = normalizeMemoryMetricFilters(next)
  void router.push({ name: 'memory-metrics', params: { appId: appId.value }, query: memoryMetricFiltersToQuery(normalized) })
}

function switchMetric(metric: MemoryMetric): void { navigate({ ...filters.value, metric }) }
function switchPercentile(percentile: MemoryPercentile): void {
  navigate({ ...filters.value, percentile })
}

function correctQuery(): void {
  void router.replace({ name: 'memory-metrics', params: { appId: appId.value }, query: memoryMetricFiltersToQuery(filters.value) })
}

function statusMessage(status: string | undefined): string {
  return status === 'no_data' ? '当前筛选范围没有可展示的数据' : '当前筛选范围没有有效采样'
}
</script>

<template>
  <AppLayout :app-id="appId">
    <header class="page-header"><div><p class="eyebrow">APP / {{ appId }}</p><h1>内存指标分析</h1><p class="subtitle">观察 PSS、VSS 与 Java 堆的采样分布和 UTC 趋势。</p></div><span v-if="query.summary.value" class="badge badge-success">数据源 · {{ query.summary.value.dataSource }}</span></header>

    <div class="metric-tabs memory-tabs" role="tablist" aria-label="内存指标类型">
      <button v-for="item in metrics" :key="item.value" type="button" role="tab" :aria-selected="filters.metric === item.value" :class="{ active: filters.metric === item.value }" @click="switchMetric(item.value)"><strong>{{ item.label }}</strong><span>{{ item.description }}</span></button>
    </div>

    <div v-if="queryWarning" class="warning-state" role="alert"><p>{{ queryWarning }}</p><button class="button" type="button" @click="correctQuery">修正地址参数</button></div>

    <MemoryMetricFilterBar :model-value="filters" :loading="query.loading.value" @submit="navigate" />

    <section class="panel jank-region">
      <div class="panel-header"><div><h2>查询范围汇总</h2><p>平均值与 P50/P90/P95/P99，单位 MiB</p></div><button v-if="query.summaryError.value" class="button" type="button" @click="query.loadSummary">重试汇总</button></div>
      <StatusMessage v-if="query.summaryError.value" kind="error" :message="query.summaryError.value" @retry="query.loadSummary" />
      <StatusMessage v-else-if="query.summaryLoading.value" kind="loading" message="正在计算内存汇总…" />
      <MemoryMetricSummary v-else-if="selectedStats" :metric="filters.metric" :stats="selectedStats" />
      <StatusMessage v-else kind="empty" :message="statusMessage(query.summary.value?.status)" />
    </section>

    <section class="panel jank-region">
      <div class="panel-header memory-trend-header"><div><h2>指标趋势</h2><p>按 UTC {{ filters.interval === 'hour' ? '小时' : '天' }}分桶，空桶断线</p></div><div class="segmented-control" role="group" aria-label="趋势分位数"><button v-for="value in ['p50', 'p90', 'p95', 'p99']" :key="value" type="button" :class="{ active: filters.percentile === value }" @click="switchPercentile(value as MemoryPercentile)">{{ value.toUpperCase() }}</button></div></div>
      <StatusMessage v-if="query.trendError.value" kind="error" :message="query.trendError.value" @retry="query.loadTrend" />
      <StatusMessage v-else-if="query.trendLoading.value" kind="loading" message="正在加载内存趋势…" />
      <MemoryMetricTrendChart v-else-if="query.trend.value && hasTrendSamples" :metric="filters.metric" :percentile="filters.percentile" :points="query.trend.value.points" />
      <StatusMessage v-else kind="empty" :message="statusMessage(query.trend.value?.status)" />
    </section>
  </AppLayout>
</template>
