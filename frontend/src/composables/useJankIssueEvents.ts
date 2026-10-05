import { type Ref } from 'vue'
import { jankApi } from '../api/jankApi'
import type { JankEventSummary, JankFilterForm } from '../types/jank'
import { toJankApiFilters } from '../utils/jankQuery'
import { createJankCursorQuery } from './useJankQuery'

export function useJankIssueEvents(
  appId: Ref<string>,
  fingerprint: Ref<string>,
  filters: Ref<JankFilterForm>,
) {
  const query = createJankCursorQuery<JankEventSummary>((event) => event.eventId)

  function snapshot() {
    return {
      appId: appId.value,
      fingerprint: fingerprint.value,
      filters: { ...filters.value, fingerprint: fingerprint.value },
    }
  }

  async function load(): Promise<void> {
    const current = snapshot()
    await query.load(async (_cursor, signal) => {
      const response = await jankApi.events(
        current.appId,
        current.fingerprint,
        toJankApiFilters(current.filters),
        signal,
      )
      return { items: response.events, nextCursor: response.nextCursor, from: response.from, to: response.to }
    })
  }

  async function loadMore(): Promise<void> {
    const current = snapshot()
    await query.loadMore(async (cursor, signal) => {
      const response = await jankApi.events(
        current.appId,
        current.fingerprint,
        toJankApiFilters({ ...current.filters, ...query.range.value }, cursor),
        signal,
      )
      return { items: response.events, nextCursor: response.nextCursor, from: response.from, to: response.to }
    })
  }

  return { ...query, load, loadMore }
}
