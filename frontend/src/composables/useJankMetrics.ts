import { computed, type Ref } from 'vue'
import { jankApi } from '../api/jankApi'
import type {
  FpsMetricsResponse,
  JankMetricFilterForm,
  MetricDimensionsResponse,
  MetricTrendResponse,
  SuspensionRateResponse,
} from '../types/jank'
import { normalizeJankMetricFilters, toJankMetricApiFilters } from '../utils/jankQuery'
import { createJankQueryRegion } from './useJankQuery'

export type JankMetricSummaryResponse = FpsMetricsResponse | SuspensionRateResponse

export function useJankMetrics(appId: Ref<string>, filters: Ref<JankMetricFilterForm>) {
  const summary = createJankQueryRegion<JankMetricSummaryResponse>()
  const trend = createJankQueryRegion<MetricTrendResponse>()
  const dimensions = createJankQueryRegion<MetricDimensionsResponse>()

  function snapshot() {
    const normalized = normalizeJankMetricFilters({ ...filters.value })
    return { appId: appId.value, filters: normalized, apiFilters: toJankMetricApiFilters(normalized) }
  }

  async function loadSummary(): Promise<void> {
    const current = snapshot()
    await summary.run((signal) => current.filters.metric === 'fps'
      ? jankApi.fps(current.appId, current.apiFilters, signal)
      : jankApi.suspensionRate(current.appId, current.apiFilters, signal))
  }

  async function loadTrend(): Promise<void> {
    const current = snapshot()
    await trend.run((signal) => jankApi.metricTrend(
      current.appId,
      current.filters.metric,
      current.filters.interval,
      current.apiFilters,
      signal,
    ))
  }

  async function loadDimensions(): Promise<void> {
    const current = snapshot()
    await dimensions.run((signal) => jankApi.dimensions(
      current.appId,
      current.filters.metric,
      current.filters.dimension,
      current.apiFilters,
      signal,
    ))
  }

  async function load(): Promise<void> {
    summary.reset()
    trend.reset()
    dimensions.reset()
    await Promise.all([loadSummary(), loadTrend(), loadDimensions()])
  }

  function cancel(): void { summary.cancel(); trend.cancel(); dimensions.cancel() }

  return {
    summary: summary.data, summaryLoading: summary.loading, summaryError: summary.error,
    trend: trend.data, trendLoading: trend.loading, trendError: trend.error,
    dimensions: dimensions.data, dimensionsLoading: dimensions.loading, dimensionsError: dimensions.error,
    loading: computed(() => summary.loading.value || trend.loading.value || dimensions.loading.value),
    load, loadSummary, loadTrend, loadDimensions, cancel,
  }
}
