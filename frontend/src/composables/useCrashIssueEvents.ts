import { ref, type Ref } from 'vue'
import { crashApi, errorMessage, isAbortError } from '../api/crashApi'
import type { CrashEventSummary, CrashFilterForm } from '../types/crash'
import { toApiFilters } from '../utils/query'

export function useCrashIssueEvents(
  appId: Ref<string>,
  fingerprint: Ref<string>,
  filters: Ref<CrashFilterForm>,
) {
  const events = ref<CrashEventSummary[]>([])
  const nextCursor = ref<string | null>(null)
  const loading = ref(false)
  const loadingMore = ref(false)
  const error = ref<string | null>(null)
  let requestToken = 0
  let controller: AbortController | null = null

  async function load() {
    const token = ++requestToken
    controller?.abort()
    controller = new AbortController()
    events.value = []
    nextCursor.value = null
    error.value = null
    loading.value = true
    try {
      const result = await crashApi.events(
        appId.value,
        fingerprint.value,
        toApiFilters(filters.value),
        controller.signal,
      )
      if (token !== requestToken) {
        return
      }
      events.value = result.events
      nextCursor.value = result.nextCursor
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

  async function loadMore() {
    if (!nextCursor.value || loadingMore.value || loading.value) {
      return
    }
    const token = requestToken
    loadingMore.value = true
    error.value = null
    try {
      const result = await crashApi.events(
        appId.value,
        fingerprint.value,
        toApiFilters(filters.value, nextCursor.value),
      )
      if (token !== requestToken) {
        return
      }
      const existing = new Set(events.value.map((event) => event.eventId))
      events.value.push(...result.events.filter((event) => !existing.has(event.eventId)))
      nextCursor.value = result.nextCursor
    } catch (requestError) {
      if (token === requestToken && !isAbortError(requestError)) {
        error.value = errorMessage(requestError)
      }
    } finally {
      if (token === requestToken) {
        loadingMore.value = false
      }
    }
  }

  return {
    events,
    nextCursor,
    loading,
    loadingMore,
    error,
    load,
    loadMore,
  }
}
