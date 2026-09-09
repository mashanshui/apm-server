import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import type {
  MemoryInterval,
  MemoryMetric,
  MemoryMetricFilterForm,
  MemoryMetricQueryFilters,
  MemoryPercentile,
} from '../types/memory'

export const MEMORY_METRICS: readonly MemoryMetric[] = ['pss', 'vss', 'java_heap']
export const MEMORY_INTERVALS: readonly MemoryInterval[] = ['hour', 'day']
export const MEMORY_PERCENTILES: readonly MemoryPercentile[] = ['p50', 'p90', 'p95', 'p99']
const MEMORY_QUERY_KEYS = new Set([
  'from', 'to', 'metric', 'interval', 'percentile', 'appVersion', 'osVersion', 'deviceModel', 'processName', 'scene', 'foreground',
])

function queryValue(query: LocationQuery, key: string): string {
  const value = query[key]
  return (Array.isArray(value) ? String(value[0] ?? '') : String(value ?? '')).trim()
}

function recentRange(now: Date): { from: string; to: string } {
  return {
    from: new Date(now.getTime() - 24 * 60 * 60 * 1000).toISOString(),
    to: now.toISOString(),
  }
}

export function createDefaultMemoryMetricFilters(now = new Date()): MemoryMetricFilterForm {
  return {
    ...recentRange(now), appVersion: '', osVersion: '', deviceModel: '', processName: '', scene: '', foreground: '',
    metric: 'pss', interval: 'hour', percentile: 'p50',
  }
}

export function parseMemoryMetricFilters(query: LocationQuery, now = new Date()): MemoryMetricFilterForm {
  const defaults = createDefaultMemoryMetricFilters(now)
  const fromValue = queryValue(query, 'from')
  const toValue = queryValue(query, 'to')
  const metricValue = queryValue(query, 'metric')
  const intervalValue = queryValue(query, 'interval')
  const percentileValue = queryValue(query, 'percentile')
  const foregroundValue = queryValue(query, 'foreground')
  return {
    from: isValidInstant(fromValue) ? fromValue : defaults.from,
    to: isValidInstant(toValue) ? toValue : defaults.to,
    appVersion: queryValue(query, 'appVersion'),
    osVersion: queryValue(query, 'osVersion'),
    deviceModel: queryValue(query, 'deviceModel'),
    processName: queryValue(query, 'processName'),
    scene: queryValue(query, 'scene'),
    foreground: foregroundValue === 'true' || foregroundValue === 'false' ? foregroundValue : '',
    metric: MEMORY_METRICS.includes(metricValue as MemoryMetric) ? metricValue as MemoryMetric : 'pss',
    interval: intervalValue === 'day' ? 'day' : 'hour',
    percentile: MEMORY_PERCENTILES.includes(percentileValue as MemoryPercentile) ? percentileValue as MemoryPercentile : 'p50',
  }
}

/** 返回地址栏中首个非法内存筛选，供页面给出可修正提示。 */
export function memoryMetricQueryWarning(query: LocationQuery): string | null {
  const unknown = Object.keys(query).find((key) => !MEMORY_QUERY_KEYS.has(key))
  if (unknown) return `地址参数“${unknown}”不适用于内存指标，请修正筛选。`
  const from = queryValue(query, 'from')
  const to = queryValue(query, 'to')
  if (from && !isValidInstant(from)) return '地址参数 from 不是有效的 ISO-8601 时间，请修正筛选。'
  if (to && !isValidInstant(to)) return '地址参数 to 不是有效的 ISO-8601 时间，请修正筛选。'
  const metric = queryValue(query, 'metric')
  if (metric && !MEMORY_METRICS.includes(metric as MemoryMetric)) return '地址参数 metric 只支持 PSS、VSS 或 Java 堆，请修正筛选。'
  const interval = queryValue(query, 'interval')
  if (interval && interval !== 'hour' && interval !== 'day') return '地址参数 interval 只支持 hour 或 day，请修正筛选。'
  const percentile = queryValue(query, 'percentile')
  if (percentile && !MEMORY_PERCENTILES.includes(percentile as MemoryPercentile)) return '地址参数 percentile 只支持 P50、P90、P95 或 P99，请修正筛选。'
  const foreground = queryValue(query, 'foreground')
  if (foreground && foreground !== 'true' && foreground !== 'false') return '地址参数 foreground 只支持 true 或 false，请修正筛选。'
  if (isValidInstant(from) && isValidInstant(to) && new Date(from).getTime() >= new Date(to).getTime()) {
    return '地址参数 from 必须早于 to，请修正筛选。'
  }
  return null
}

function optionalQuery(filters: MemoryMetricFilterForm): LocationQueryRaw {
  const result: LocationQueryRaw = {}
  for (const field of ['appVersion', 'osVersion', 'deviceModel', 'processName', 'scene', 'foreground'] as const) {
    if (filters[field]) result[field] = filters[field]
  }
  return result
}

export function memoryMetricFiltersToQuery(filters: MemoryMetricFilterForm): LocationQueryRaw {
  const normalized = normalizeMemoryMetricFilters(filters)
  return {
    from: normalized.from, to: normalized.to, metric: normalized.metric, interval: normalized.interval,
    percentile: normalized.percentile, ...optionalQuery(normalized),
  }
}

export function toMemoryApiFilters(filters: MemoryMetricFilterForm): MemoryMetricQueryFilters {
  const normalized = normalizeMemoryMetricFilters(filters)
  const result: MemoryMetricQueryFilters = {
    from: normalized.from, to: normalized.to, limit: 500, timeoutMs: 2_000,
  }
  for (const field of ['appVersion', 'osVersion', 'deviceModel', 'processName', 'scene'] as const) {
    if (normalized[field].trim()) result[field] = normalized[field].trim()
  }
  if (normalized.foreground) result.foreground = normalized.foreground === 'true'
  return result
}

export function normalizeMemoryMetricFilters(filters: MemoryMetricFilterForm): MemoryMetricFilterForm {
  return {
    ...filters,
    appVersion: filters.appVersion.trim(), osVersion: filters.osVersion.trim(), deviceModel: filters.deviceModel.trim(),
    processName: filters.processName.trim(), scene: filters.scene.trim(),
    foreground: filters.foreground === 'true' || filters.foreground === 'false' ? filters.foreground : '',
    metric: MEMORY_METRICS.includes(filters.metric) ? filters.metric : 'pss',
    interval: filters.interval === 'day' ? 'day' : 'hour',
    percentile: MEMORY_PERCENTILES.includes(filters.percentile) ? filters.percentile : 'p50',
  }
}

function isValidInstant(value: string): boolean {
  if (!value || !/(?:Z|[+-]\d{2}:?\d{2})$/i.test(value)) return false
  return !Number.isNaN(new Date(value).getTime())
}
