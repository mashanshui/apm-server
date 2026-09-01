import { ref, type Ref } from 'vue'
import { crashApi, errorMessage, isAbortError } from '../api/crashApi'
import type {
  CrashFilterForm,
  CrashIssueSummary,
  CrashIssueResponse,
  CrashOverviewResponse,
  CrashTrendResponse,
} from '../types/crash'
import { toApiFilters } from '../utils/query'

export function useCrashOverview(appId: Ref<string>, filters: Ref<CrashFilterForm>) {
  const overview = ref<CrashOverviewResponse | null>(null)
  const trend = ref<CrashTrendResponse | null>(null)
  const issues = ref<CrashIssueSummary[]>([])
  const nextCursor = ref<string | null>(null)
  const loading = ref(false)
  const loadingIssues = ref(false)
  const error = ref<string | null>(null)
  const issueError = ref<string | null>(null)

  let requestToken = 0
  let controller: AbortController | null = null

  async function load() {
    const token = ++requestToken
    controller?.abort()
    controller = new AbortController()
    overview.value = null
    trend.value = null
    issues.value = []
    nextCursor.value = null
    error.value = null
    issueError.value = null
    loading.value = true

    try {
      const requestFilters = toApiFilters(filters.value)
      const [overviewResult, trendResult, issueResult] = await Promise.all([
        crashApi.overview(appId.value, requestFilters, controller.signal),
        crashApi.trend(appId.value, requestFilters, filters.value.interval, controller.signal),
        crashApi.issues(appId.value, requestFilters, controller.signal),
      ])
      if (token !== requestToken) {
        return
      }
      overview.value = overviewResult
      trend.value = trendResult
      issues.value = issueResult.issues
      nextCursor.value = issueResult.nextCursor
    } catch (requestError) {
      if (token === requestToken && !isAbortError(requestError)) {
        error.value = errorMessage(requestError)
      }
    } finally {
      if (token === requestToken) {
        loading.value = false
      }
    }
  }

  async function loadMoreIssues() {
    if (!nextCursor.value || loadingIssues.value || loading.value) {
      return
    }
    const token = requestToken
    loadingIssues.value = true
    issueError.value = null
    try {
      const result: CrashIssueResponse = await crashApi.issues(
        appId.value,
        toApiFilters(filters.value, nextCursor.value),
      )
      if (token !== requestToken) {
        return
      }
      const existing = new Set(issues.value.map((issue) => issue.fingerprint))
      issues.value.push(...result.issues.filter((issue) => !existing.has(issue.fingerprint)))
      nextCursor.value = result.nextCursor
    } catch (requestError) {
      if (token === requestToken && !isAbortError(requestError)) {
        issueError.value = errorMessage(requestError)
      }
    } finally {
      if (token === requestToken) {
        loadingIssues.value = false
      }
    }
  }

  return {
    overview,
    trend,
    issues,
    nextCursor,
    loading,
    loadingIssues,
    error,
    issueError,
    load,
    loadMoreIssues,
  }
}
