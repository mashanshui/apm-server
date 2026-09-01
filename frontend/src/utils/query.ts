import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import type { CrashFilterForm, CrashQueryFilters } from '../types/crash'

const queryValue = (query: LocationQuery, key: string): string => {
  const value = query[key]
  return Array.isArray(value) ? String(value[0] ?? '') : String(value ?? '')
}

export function createDefaultFilters(now = new Date()): CrashFilterForm {
  const to = now.toISOString()
  const from = new Date(now.getTime() - 24 * 60 * 60 * 1000).toISOString()
  return {
    from,
    to,
    appVersion: '',
    channel: '',
    environment: '',
    osVersion: '',
    deviceModel: '',
    fingerprint: '',
    interval: 'hour',
  }
}

export function parseFilters(query: LocationQuery): CrashFilterForm {
  const defaults = createDefaultFilters()
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
    interval: interval === 'day' ? 'day' : 'hour',
  }
}

export function filtersToQuery(filters: CrashFilterForm): LocationQueryRaw {
  const query: LocationQueryRaw = {
    from: filters.from,
    to: filters.to,
    interval: filters.interval,
  }
  const optionalFields: Array<keyof Omit<CrashFilterForm, 'from' | 'to' | 'interval'>> = [
    'appVersion',
    'channel',
    'environment',
    'osVersion',
    'deviceModel',
    'fingerprint',
  ]
  for (const field of optionalFields) {
    if (filters[field]) {
      query[field] = filters[field]
    }
  }
  return query
}

export function toApiFilters(filters: CrashFilterForm, cursor?: string): CrashQueryFilters {
  const result: CrashQueryFilters = {
    from: filters.from,
    to: filters.to,
    limit: 50,
    timeoutMs: 2000,
  }
  const optionalFields: Array<keyof Omit<CrashFilterForm, 'from' | 'to' | 'interval'>> = [
    'appVersion',
    'channel',
    'environment',
    'osVersion',
    'deviceModel',
    'fingerprint',
  ]
  for (const field of optionalFields) {
    const value = filters[field]
    if (value) {
      result[field] = value
    }
  }
  if (cursor) {
    result.cursor = cursor
  }
  return result
}

export function cloneFilters(filters: CrashFilterForm): CrashFilterForm {
  return { ...filters }
}
