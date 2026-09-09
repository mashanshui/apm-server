import type {
  MemoryInterval,
  MemoryMetric,
  MemoryMetricQueryFilters,
  MemoryMetricsSummaryResponse,
  MemoryTrendResponse,
} from '../types/memory'
import {
  ApiError,
  errorMessage as sharedErrorMessage,
  isAbortError as sharedIsAbortError,
  pathSegment,
  requestJson as request,
} from './http'

export { ApiError as MemoryApiError }

const queryKeys = [
  'from', 'to', 'appVersion', 'osVersion', 'deviceModel', 'processName', 'scene', 'foreground', 'limit', 'timeoutMs',
] as const

function buildQuery(filters: MemoryMetricQueryFilters = {}): URLSearchParams {
  const params = new URLSearchParams()
  const values = filters as Record<string, unknown>
  for (const key of queryKeys) {
    const value = values[key]
    if (value !== undefined && value !== null && value !== '') {
      params.set(key, String(value))
    }
  }
  return params
}

export function buildMemoryQueryParams(filters: MemoryMetricQueryFilters = {}): URLSearchParams {
  return buildQuery(filters)
}

function query<T>(path: string, params: URLSearchParams, signal?: AbortSignal): Promise<T> {
  const text = params.toString()
  return request<T>(`${path}${text ? `?${text}` : ''}`, { signal })
}

export const memoryApi = {
  summary(appId: string, filters: MemoryMetricQueryFilters = {}, signal?: AbortSignal) {
    return query<MemoryMetricsSummaryResponse>(
      `/api/v1/apps/${pathSegment(appId)}/memory-metrics/summary`, buildQuery(filters), signal,
    )
  },

  trend(appId: string, metric: MemoryMetric, interval: MemoryInterval, filters: MemoryMetricQueryFilters = {}, signal?: AbortSignal) {
    const params = buildQuery(filters)
    params.set('metric', metric)
    params.set('interval', interval)
    return query<MemoryTrendResponse>(
      `/api/v1/apps/${pathSegment(appId)}/memory-metrics/trend`, params, signal,
    )
  },
}

export function isAbortError(error: unknown): boolean {
  return sharedIsAbortError(error)
}

export function errorMessage(error: unknown): string {
  return sharedErrorMessage(error)
}
