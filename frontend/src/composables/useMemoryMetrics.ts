import { computed, type Ref } from 'vue'
import { memoryApi } from '../api/memoryApi'
import type { MemoryMetricFilterForm, MemoryMetricsSummaryResponse, MemoryTrendResponse } from '../types/memory'
import { normalizeMemoryMetricFilters, toMemoryApiFilters } from '../utils/memoryQuery'
import { createJankQueryRegion } from './useJankQuery'

export function useMemoryMetrics(appId: Ref<string>, filters: Ref<MemoryMetricFilterForm>) {
  const summary = createJankQueryRegion<MemoryMetricsSummaryResponse>()
  const trend = createJankQueryRegion<MemoryTrendResponse>()

  function snapshot() {
    const normalized = normalizeMemoryMetricFilters({ ...filters.value })
    return { appId: appId.value, filters: normalized, apiFilters: toMemoryApiFilters(normalized) }
  }

  async function loadSummary(): Promise<void> {
    const current = snapshot()
    await summary.run((signal) => memoryApi.summary(current.appId, current.apiFilters, signal))
  }

  async function loadTrend(): Promise<void> {
    const current = snapshot()
    await trend.run((signal) => memoryApi.trend(current.appId, current.filters.metric, current.filters.interval,
      current.apiFilters, signal))
  }

  async function load(): Promise<void> {
    summary.reset(); trend.reset()
    await Promise.all([loadSummary(), loadTrend()])
  }

  function cancel(): void { summary.cancel(); trend.cancel() }

  return {
    summary: summary.data, summaryLoading: summary.loading, summaryError: summary.error,
    trend: trend.data, trendLoading: trend.loading, trendError: trend.error,
    loading: computed(() => summary.loading.value || trend.loading.value), load, loadSummary, loadTrend, cancel,
  }
}
