import { computed, type Ref } from 'vue'
import { jankApi } from '../api/jankApi'
import type {
  JankFilterForm,
  JankIssueResponse,
  JankIssueSummary,
  JankOverviewResponse,
  JankTrendResponse,
} from '../types/jank'
import { toJankApiFilters } from '../utils/jankQuery'
import { createJankCursorQuery, createJankQueryRegion } from './useJankQuery'

export function useJankIssues(appId: Ref<string>, filters: Ref<JankFilterForm>) {
  const overview = createJankQueryRegion<JankOverviewResponse>()
  const trend = createJankQueryRegion<JankTrendResponse>()
  const issues = createJankCursorQuery<JankIssueSummary>((issue) => issue.fingerprint)

  function querySnapshot() {
    return {
      appId: appId.value,
      filters: { ...filters.value },
    }
  }

  async function loadOverview(): Promise<void> {
    const snapshot = querySnapshot()
    await overview.run((signal) => jankApi.overview(
      snapshot.appId,
      toJankApiFilters(snapshot.filters),
      signal,
    ))
  }

  async function loadTrend(): Promise<void> {
    const snapshot = querySnapshot()
    await trend.run((signal) => jankApi.trend(
      snapshot.appId,
      toJankApiFilters(snapshot.filters),
      snapshot.filters.interval,
      signal,
    ))
  }

  async function loadIssues(): Promise<void> {
    const snapshot = querySnapshot()
    await issues.load(async (_cursor, signal) => {
      const response: JankIssueResponse = await jankApi.issues(
        snapshot.appId,
        toJankApiFilters(snapshot.filters),
        signal,
      )
      return { items: response.issues, nextCursor: response.nextCursor, from: response.from, to: response.to }
    })
  }

  async function load(): Promise<void> {
    overview.reset()
    trend.reset()
    issues.reset()
    await Promise.all([loadOverview(), loadTrend(), loadIssues()])
  }

  async function loadMoreIssues(): Promise<void> {
    const snapshot = querySnapshot()
    await issues.loadMore(async (cursor, signal) => {
      const response = await jankApi.issues(
        snapshot.appId,
        toJankApiFilters({ ...snapshot.filters, ...issues.range.value }, cursor),
        signal,
      )
      return { items: response.issues, nextCursor: response.nextCursor, from: response.from, to: response.to }
    })
  }

  function cancel(): void {
    overview.cancel()
    trend.cancel()
    issues.cancel()
  }

  return {
    overview: overview.data,
    overviewLoading: overview.loading,
    overviewError: overview.error,
    trend: trend.data,
    trendLoading: trend.loading,
    trendError: trend.error,
    issues: issues.items,
    nextCursor: issues.nextCursor,
    issuesLoading: issues.loading,
    issuesLoadingMore: issues.loadingMore,
    issuesError: issues.error,
    issueAppendError: issues.appendError,
    issueCursorInvalid: issues.cursorInvalid,
    loading: computed(() => overview.loading.value || trend.loading.value || issues.loading.value),
    load,
    loadOverview,
    loadTrend,
    loadIssues,
    loadMoreIssues,
    cancel,
  }
}
