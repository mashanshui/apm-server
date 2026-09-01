<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppLayout from '../components/AppLayout.vue'
import JankMetricDimensionsTable from '../components/JankMetricDimensionsTable.vue'
import JankMetricFilterBar from '../components/JankMetricFilterBar.vue'
import JankMetricSummary from '../components/JankMetricSummary.vue'
import JankMetricTrendChart from '../components/JankMetricTrendChart.vue'
import StatusMessage from '../components/StatusMessage.vue'
import { useJankMetrics } from '../composables/useJankMetrics'
import type { JankMetric, JankMetricFilterForm } from '../types/jank'
import { jankMetricFiltersToQuery, normalizeJankMetricFilters, parseJankMetricFilters } from '../utils/jankQuery'

const route = useRoute()
const router = useRouter()
const appId = computed(() => String(route.params.appId))
const filters = ref<JankMetricFilterForm>(parseJankMetricFilters(route.query))
const query = useJankMetrics(appId, filters)

watch(() => route.fullPath, () => {
  filters.value = parseJankMetricFilters(route.query)
  void query.load()
}, { immediate: true })
onBeforeUnmount(query.cancel)

function navigate(next: JankMetricFilterForm): void {
  const normalized = normalizeJankMetricFilters(next)
  void router.push({ name: 'jank-metrics', params: { appId: appId.value }, query: jankMetricFiltersToQuery(normalized) })
}
function switchMetric(metric: JankMetric): void {
  navigate({ ...filters.value, metric })
}
function statusMessage(status: string | undefined): string {
  if (status === 'no_data') return '当前筛选范围没有上报记录'
  if (status === 'no_valid_data') return '当前范围没有有效 FPS 记录，统计值保持为空'
  if (status === 'denominator_insufficient') return '当前范围的前台时长分母不足，挂起率保持为空'
  return '当前范围没有可展示的数据'
}
</script>

<template>
  <AppLayout :app-id="appId">
    <header class="page-header"><div><p class="eyebrow">APP / {{ appId }}</p><h1>卡顿指标分析</h1><p class="subtitle">观察场景 FPS 与设备日挂起率，再按白名单维度定位分布差异。</p></div><span v-if="query.summary.value" class="badge badge-success">数据源 · {{ query.summary.value.dataSource }}</span></header>

    <div class="metric-tabs" role="tablist" aria-label="卡顿指标类型">
      <button type="button" role="tab" :aria-selected="filters.metric === 'fps'" :class="{ active: filters.metric === 'fps' }" @click="switchMetric('fps')"><strong>场景 FPS</strong><span>帧/秒，分位数从高到低解释</span></button>
      <button type="button" role="tab" :aria-selected="filters.metric === 'suspension_rate'" :class="{ active: filters.metric === 'suspension_rate' }" @click="switchMetric('suspension_rate')"><strong>设备日挂起率</strong><span>秒/小时前台时长，固定 UTC 天</span></button>
    </div>

    <JankMetricFilterBar :model-value="filters" :loading="query.loading.value" @submit="navigate" />

    <section class="panel jank-region">
      <div class="panel-header"><div><h2>查询范围汇总</h2><p>按算法版本独立展示平均值、分位数和有效记录</p></div><button v-if="query.summaryError.value" class="button" type="button" @click="query.loadSummary">重试汇总</button></div>
      <StatusMessage v-if="query.summaryError.value" kind="error" :message="query.summaryError.value" @retry="query.loadSummary" />
      <StatusMessage v-else-if="query.summaryLoading.value" kind="loading" message="正在计算指标汇总…" />
      <JankMetricSummary v-else-if="query.summary.value" :metric="filters.metric" :response="query.summary.value" />
      <StatusMessage v-else kind="empty" :message="statusMessage(undefined)" />
    </section>

    <section class="panel jank-region">
      <div class="panel-header"><div><h2>指标趋势</h2><p>{{ filters.metric === 'fps' ? `按${filters.interval === 'hour' ? '小时' : 'UTC 天'}展示，缺失桶不连线` : '按 UTC 设备日展示，原始区间先合并后计算' }}</p></div><button v-if="query.trendError.value" class="button" type="button" @click="query.loadTrend">重试趋势</button></div>
      <StatusMessage v-if="query.trendError.value" kind="error" :message="query.trendError.value" @retry="query.loadTrend" />
      <StatusMessage v-else-if="query.trendLoading.value" kind="loading" message="正在加载指标趋势…" />
      <JankMetricTrendChart v-else-if="query.trend.value?.points.length" :metric="filters.metric" :points="query.trend.value.points" />
      <StatusMessage v-else kind="empty" :message="statusMessage(query.trend.value?.status)" />
    </section>

    <section class="panel jank-region">
      <div class="panel-header"><div><h2>多维分析 · {{ filters.dimension }}</h2><p>{{ filters.metric === 'fps' ? '总记录与有效 FPS 记录分别统计' : '原始区间数与有效设备日记录分别统计' }}</p></div><button v-if="query.dimensionsError.value" class="button" type="button" @click="query.loadDimensions">重试多维</button></div>
      <StatusMessage v-if="query.dimensionsError.value" kind="error" :message="query.dimensionsError.value" @retry="query.loadDimensions" />
      <StatusMessage v-else-if="query.dimensionsLoading.value" kind="loading" message="正在加载多维分析…" />
      <JankMetricDimensionsTable v-else-if="query.dimensions.value" :metric="filters.metric" :dimension="filters.dimension" :points="query.dimensions.value.points" />
      <StatusMessage v-else kind="empty" :message="statusMessage(undefined)" />
    </section>
  </AppLayout>
</template>
