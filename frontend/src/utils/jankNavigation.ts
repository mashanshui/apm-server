import type { LocationQuery, RouteLocationRaw } from 'vue-router'
import { jankFiltersToQuery, jankMetricFiltersToQuery, parseJankFilters, parseJankMetricFilters } from './jankQuery'
import { memoryMetricFiltersToQuery, parseMemoryMetricFilters } from './memoryQuery'

export function jankAppSwitchTarget(
  routeName: string | symbol | null | undefined,
  appId: string,
  query: LocationQuery,
): RouteLocationRaw | null {
  const name = String(routeName ?? '')
  if (name === 'jank-metrics') {
    return {
      name: 'jank-metrics',
      params: { appId },
      query: jankMetricFiltersToQuery(parseJankMetricFilters(query)),
    }
  }
  if (name === 'memory-metrics') {
    return {
      name: 'memory-metrics',
      params: { appId },
      query: memoryMetricFiltersToQuery(parseMemoryMetricFilters(query)),
    }
  }
  if (['jank-issues', 'jank-issue-events', 'jank-event-detail'].includes(name)) {
    return {
      name: 'jank-issues',
      params: { appId },
      query: jankFiltersToQuery({ ...parseJankFilters(query), fingerprint: '' }),
    }
  }
  return null
}
