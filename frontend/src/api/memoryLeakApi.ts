import type {
  MemoryLeakIssuesResponse,
  MemoryLeakQueryFilters,
  MemoryLeakTrendResponse,
} from '../types/memoryLeak'
import {
  ApiError,
  errorMessage as sharedErrorMessage,
  isAbortError as sharedIsAbortError,
  pathSegment,
  requestJson as request,
} from './http'

export { ApiError as MemoryLeakApiError }

const commonQueryKeys = [
  'from', 'to', 'appVersion', 'deviceModel', 'processName', 'scene', 'manufacturer', 'sdkInt',
  'dumpReason', 'anonymousDeviceId', 'signature', 'keyword',
] as const

const issueQueryKeys = [...commonQueryKeys, 'page', 'pageSize', 'sort', 'order'] as const
const trendQueryKeys = [...commonQueryKeys, 'interval'] as const

type QueryEndpoint = 'issues' | 'trend'

export function buildMemoryLeakQueryParams(
  filters: MemoryLeakQueryFilters = {}, endpoint: QueryEndpoint = 'issues',
): URLSearchParams {
  const params = new URLSearchParams()
  const values = filters as Record<string, unknown>
  const keys = endpoint === 'trend' ? trendQueryKeys : issueQueryKeys
  for (const key of keys) {
    const value = values[key]
    if (value !== undefined && value !== null && value !== '') {
      params.set(key, String(value))
    }
  }
  return params
}

function query<T>(path: string, filters: MemoryLeakQueryFilters, endpoint: QueryEndpoint, signal?: AbortSignal): Promise<T> {
  const text = buildMemoryLeakQueryParams(filters, endpoint).toString()
  return request<T>(`${path}${text ? `?${text}` : ''}`, { signal })
}

export const memoryLeakApi = {
  issues(appId: string, filters: MemoryLeakQueryFilters, signal?: AbortSignal) {
    return query<MemoryLeakIssuesResponse>(
      `/api/v1/apps/${pathSegment(appId)}/memory-leaks/issues`, filters, 'issues', signal,
    )
  },

  trend(appId: string, filters: MemoryLeakQueryFilters, signal?: AbortSignal) {
    return query<MemoryLeakTrendResponse>(
      `/api/v1/apps/${pathSegment(appId)}/memory-leaks/trend`, filters, 'trend', signal,
    )
  },
}

export function isAbortError(error: unknown): boolean {
  return sharedIsAbortError(error)
}

export function errorMessage(error: unknown): string {
  return sharedErrorMessage(error)
}
