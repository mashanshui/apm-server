import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import type {
  JankDimension,
  JankFilterForm,
  JankInterval,
  JankMetric,
  JankMetricFilterForm,
  JankMetricQueryFilters,
  JankQueryFilters,
} from '../types/jank'

export const JANK_INTERVALS: readonly JankInterval[] = ['hour', 'day']
export const JANK_METRICS: readonly JankMetric[] = ['fps', 'suspension_rate']
export const JANK_DIMENSIONS: readonly JankDimension[] = [
  'appVersion',
  'channel',
  'environment',
  'osVersion',
  'deviceModel',
  'scene',
  'algorithmVersion',
]

const commonFields = [
  'appVersion',
  'channel',
  'environment',
  'osVersion',
  'deviceModel',
  'scene',
  'algorithmVersion',
] as const

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

function optionalQuery(form: Record<string, string>, fields: readonly string[]): LocationQueryRaw {
  const result: LocationQueryRaw = {}
  for (const field of fields) {
    const value = form[field]?.trim()
    if (value) {
      result[field] = value
    }
  }
  return result
}

export function createDefaultJankFilters(now = new Date()): JankFilterForm {
  return {
    ...recentRange(now),
    appVersion: '',
    channel: '',
    environment: '',
    osVersion: '',
    deviceModel: '',
    fingerprint: '',
    scene: '',
    algorithmVersion: '',
    interval: 'hour',
  }
}

export function parseJankFilters(query: LocationQuery, now = new Date()): JankFilterForm {
  const defaults = createDefaultJankFilters(now)
  const interval = queryValue(query, 'interval')
  return {
    from: queryValue(query, 'from') || defaults.from,
    to: queryValue(query, 'to') || defaults.to,
    appVersion: queryValue(query, 'appVersion'),
    channel: queryValue(query, 'channel'),
    environment: queryValue(query, 'environment'),
    osVersion: queryValue(query, 'osVersion'),
    deviceModel: queryValue(query, 'deviceModel'),
    fingerprint: queryValue(query, 'fingerprint'),
    scene: queryValue(query, 'scene'),
    algorithmVersion: queryValue(query, 'algorithmVersion'),
    interval: interval === 'day' ? 'day' : 'hour',
  }
}

export function jankFiltersToQuery(filters: JankFilterForm): LocationQueryRaw {
  return {
    from: filters.from,
    to: filters.to,
    interval: filters.interval,
    ...optionalQuery(filters as unknown as Record<string, string>, [...commonFields, 'fingerprint']),
  }
}

export function toJankApiFilters(filters: JankFilterForm, cursor?: string): JankQueryFilters {
  const result: JankQueryFilters = {
    from: filters.from,
    to: filters.to,
    limit: 50,
    timeoutMs: 2_000,
  }
  for (const field of [...commonFields, 'fingerprint'] as const) {
    const value = filters[field].trim()
    if (value) {
      result[field] = value
    }
  }
  if (cursor) {
    result.cursor = cursor
  }
  return result
}

export function createDefaultJankMetricFilters(now = new Date()): JankMetricFilterForm {
  return {
    ...recentRange(now),
    appVersion: '',
    channel: '',
    environment: '',
    osVersion: '',
    deviceModel: '',
    scene: '',
    algorithmVersion: '',
    metric: 'fps',
    interval: 'hour',
    dimension: 'scene',
  }
}

export function parseJankMetricFilters(query: LocationQuery, now = new Date()): JankMetricFilterForm {
  const defaults = createDefaultJankMetricFilters(now)
  const metricValue = queryValue(query, 'metric')
  const metric: JankMetric = metricValue === 'suspension_rate' ? 'suspension_rate' : 'fps'
  const intervalValue = queryValue(query, 'interval')
  const interval: JankInterval = metric === 'suspension_rate' ? 'day' : intervalValue === 'day' ? 'day' : 'hour'
  const dimensionValue = queryValue(query, 'dimension')
  let dimension: JankDimension = JANK_DIMENSIONS.includes(dimensionValue as JankDimension)
    ? dimensionValue as JankDimension
    : defaults.dimension
  if (metric === 'suspension_rate' && dimension === 'scene') {
    dimension = 'deviceModel'
  }
  return {
    from: queryValue(query, 'from') || defaults.from,
    to: queryValue(query, 'to') || defaults.to,
    appVersion: queryValue(query, 'appVersion'),
    channel: queryValue(query, 'channel'),
    environment: queryValue(query, 'environment'),
    osVersion: queryValue(query, 'osVersion'),
    deviceModel: queryValue(query, 'deviceModel'),
    scene: metric === 'fps' ? queryValue(query, 'scene') : '',
    algorithmVersion: queryValue(query, 'algorithmVersion'),
    metric,
    interval,
    dimension,
  }
}

export function jankMetricFiltersToQuery(filters: JankMetricFilterForm): LocationQueryRaw {
  const normalized = normalizeJankMetricFilters(filters)
  return {
    from: normalized.from,
    to: normalized.to,
    metric: normalized.metric,
    interval: normalized.interval,
    dimension: normalized.dimension,
    ...optionalQuery(normalized as unknown as Record<string, string>, commonFields),
  }
}

export function toJankMetricApiFilters(filters: JankMetricFilterForm): JankMetricQueryFilters {
  const normalized = normalizeJankMetricFilters(filters)
  const result: JankMetricQueryFilters = {
    from: normalized.from,
    to: normalized.to,
    limit: 50,
    timeoutMs: 2_000,
  }
  for (const field of commonFields) {
    const value = normalized[field].trim()
    if (value) {
      result[field] = value
    }
  }
  return result
}

export function normalizeJankMetricFilters(filters: JankMetricFilterForm): JankMetricFilterForm {
  if (filters.metric === 'fps') {
    return { ...filters }
  }
  return {
    ...filters,
    interval: 'day',
    scene: '',
    dimension: filters.dimension === 'scene' ? 'deviceModel' : filters.dimension,
  }
}
