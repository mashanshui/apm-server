import { computed, type Ref } from 'vue'
import { memoryLeakApi } from '../api/memoryLeakApi'
import type { MemoryLeakFilterForm, MemoryLeakIssuesResponse, MemoryLeakTrendResponse } from '../types/memoryLeak'
import { toMemoryLeakApiFilters } from '../utils/memoryLeakQuery'
import { createJankQueryRegion } from './useJankQuery'

/** 内存泄漏页的列表与趋势各自请求，避免一块失败时覆盖另一块的可用结果。 */
export function useMemoryLeakReports(appId: Ref<string>, filters: Ref<MemoryLeakFilterForm>) {
  const issues = createJankQueryRegion<MemoryLeakIssuesResponse>()
  const trend = createJankQueryRegion<MemoryLeakTrendResponse>()

  function snapshot() {
    const current = { ...filters.value }
    return { appId: appId.value, filters: current, apiFilters: toMemoryLeakApiFilters(current) }
  }

  async function loadIssues(): Promise<void> {
    const current = snapshot()
    await issues.run((signal) => memoryLeakApi.issues(current.appId, current.apiFilters, signal))
  }

  async function loadTrend(): Promise<void> {
    const current = snapshot()
    await trend.run((signal) => memoryLeakApi.trend(current.appId, current.apiFilters, signal))
  }

  async function load(): Promise<void> {
    issues.reset()
    trend.reset()
    await Promise.all([loadIssues(), loadTrend()])
  }

  function cancel(): void {
    issues.cancel()
    trend.cancel()
  }

  return {
    issues: issues.data,
    issuesLoading: issues.loading,
    issuesError: issues.error,
    trend: trend.data,
    trendLoading: trend.loading,
    trendError: trend.error,
    loading: computed(() => issues.loading.value || trend.loading.value),
    load,
    loadIssues,
    loadTrend,
    cancel,
  }
}
