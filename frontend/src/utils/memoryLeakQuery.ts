import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import type {
  MemoryLeakFilterForm,
  MemoryLeakIssueSort,
  MemoryLeakInterval,
  MemoryLeakOrder,
  MemoryLeakQueryFilters,
  MemoryLeakTrendMetric,
} from '../types/memoryLeak'

export const MEMORY_LEAK_INTERVALS: readonly MemoryLeakInterval[] = ['5m', 'hour', 'day']
export const MEMORY_LEAK_SORTS: readonly MemoryLeakIssueSort[] = ['occurrences', 'affectedDevices', 'lastOccurredAt']
export const MEMORY_LEAK_ORDERS: readonly MemoryLeakOrder[] = ['asc', 'desc']
export const MEMORY_LEAK_TREND_METRICS: readonly MemoryLeakTrendMetric[] = ['occurrences', 'affectedDevices']

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

export function createDefaultMemoryLeakFilters(now = new Date()): MemoryLeakFilterForm {
  return {
    ...recentRange(now),
    appVersion: '', deviceModel: '', processName: '', scene: '', manufacturer: '', sdkInt: '', dumpReason: '',
    anonymousDeviceId: '', signature: '', keyword: '', page: 1, pageSize: 20, sort: 'occurrences', order: 'desc',
    interval: 'hour', trendMetric: 'occurrences',
  }
}

function positiveInteger(value: string, fallback: number, max: number): number {
  const parsed = Number(value)
  return Number.isInteger(parsed) && parsed > 0 ? Math.min(parsed, max) : fallback
}

export function parseMemoryLeakFilters(query: LocationQuery, now = new Date()): MemoryLeakFilterForm {
  const defaults = createDefaultMemoryLeakFilters(now)
  const interval = queryValue(query, 'interval') as MemoryLeakInterval
  const sort = queryValue(query, 'sort') as MemoryLeakIssueSort
  const order = queryValue(query, 'order') as MemoryLeakOrder
  const trendMetric = queryValue(query, 'trendMetric') as MemoryLeakTrendMetric
  return {
    ...defaults,
    from: queryValue(query, 'from') || defaults.from,
    to: queryValue(query, 'to') || defaults.to,
    appVersion: queryValue(query, 'appVersion'), deviceModel: queryValue(query, 'deviceModel'),
    processName: queryValue(query, 'processName'), scene: queryValue(query, 'scene'),
    manufacturer: queryValue(query, 'manufacturer'), sdkInt: queryValue(query, 'sdkInt'),
    dumpReason: queryValue(query, 'dumpReason'), anonymousDeviceId: queryValue(query, 'anonymousDeviceId'),
    signature: queryValue(query, 'signature'), keyword: queryValue(query, 'keyword'),
    page: positiveInteger(queryValue(query, 'page'), 1, 10_000),
    pageSize: positiveInteger(queryValue(query, 'pageSize'), 20, 100),
    sort: MEMORY_LEAK_SORTS.includes(sort) ? sort : defaults.sort,
    order: MEMORY_LEAK_ORDERS.includes(order) ? order : defaults.order,
    interval: MEMORY_LEAK_INTERVALS.includes(interval) ? interval : defaults.interval,
    trendMetric: MEMORY_LEAK_TREND_METRICS.includes(trendMetric) ? trendMetric : defaults.trendMetric,
  }
}

const optionalFields = [
  'appVersion', 'deviceModel', 'processName', 'scene', 'manufacturer', 'sdkInt', 'dumpReason',
  'anonymousDeviceId', 'signature', 'keyword',
] as const

export function memoryLeakFiltersToQuery(filters: MemoryLeakFilterForm): LocationQueryRaw {
  const result: LocationQueryRaw = {
    from: filters.from,
    to: filters.to,
    page: String(filters.page),
    pageSize: String(filters.pageSize),
    sort: filters.sort,
    order: filters.order,
    interval: filters.interval,
    trendMetric: filters.trendMetric,
  }
  for (const field of optionalFields) {
    const value = filters[field].trim()
    if (value) result[field] = value
  }
  return result
}

export function toMemoryLeakApiFilters(filters: MemoryLeakFilterForm): MemoryLeakQueryFilters {
  const result: MemoryLeakQueryFilters = {
    from: filters.from, to: filters.to, page: filters.page, pageSize: filters.pageSize,
    sort: filters.sort, order: filters.order, interval: filters.interval,
  }
  for (const field of optionalFields) {
    const value = filters[field].trim()
    if (value) result[field] = value
  }
  return result
}

export function normalizeMemoryLeakFilters(filters: MemoryLeakFilterForm): MemoryLeakFilterForm {
  return {
    ...filters,
    appVersion: filters.appVersion.trim(), deviceModel: filters.deviceModel.trim(), processName: filters.processName.trim(),
    scene: filters.scene.trim(), manufacturer: filters.manufacturer.trim(), sdkInt: filters.sdkInt.trim(),
    dumpReason: filters.dumpReason.trim(), anonymousDeviceId: filters.anonymousDeviceId.trim(),
    signature: filters.signature.trim(), keyword: filters.keyword.trim(),
    page: Number.isInteger(filters.page) && filters.page > 0 ? filters.page : 1,
    pageSize: Number.isInteger(filters.pageSize) && filters.pageSize > 0 ? Math.min(filters.pageSize, 100) : 20,
    sort: MEMORY_LEAK_SORTS.includes(filters.sort) ? filters.sort : 'occurrences',
    order: MEMORY_LEAK_ORDERS.includes(filters.order) ? filters.order : 'desc',
    interval: MEMORY_LEAK_INTERVALS.includes(filters.interval) ? filters.interval : 'hour',
    trendMetric: MEMORY_LEAK_TREND_METRICS.includes(filters.trendMetric) ? filters.trendMetric : 'occurrences',
  }
}
