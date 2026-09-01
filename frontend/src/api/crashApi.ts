import type {
  CrashEventDetailResponse,
  CrashEventListResponse,
  CrashIssueResponse,
  CrashOverviewResponse,
  CrashQueryFilters,
  CrashTrendResponse,
} from '../types/crash'
import { ApiError, errorMessage as sharedErrorMessage, isAbortError as sharedIsAbortError, pathSegment, requestJson as request } from './http'

export { ApiError as CrashApiError }

const queryKeys: Array<keyof CrashQueryFilters> = [
  'from',
  'to',
  'appVersion',
  'channel',
  'environment',
  'osVersion',
  'deviceModel',
  'fingerprint',
  'limit',
  'cursor',
  'timeoutMs',
]

export function buildQueryParams(filters: CrashQueryFilters = {}): URLSearchParams {
  const params = new URLSearchParams()
  for (const key of queryKeys) {
    const value = filters[key]
    if (value !== undefined && value !== null && value !== '') {
      params.set(key, String(value))
    }
  }
  return params
}

async function requestJson<T>(
  appId: string,
  path: string,
  filters: CrashQueryFilters = {},
  signal?: AbortSignal,
  extraParams: Record<string, string> = {},
): Promise<T> {
  const query = buildQueryParams(filters)
  for (const [key, value] of Object.entries(extraParams)) {
    query.set(key, value)
  }
  const queryText = query.toString()
  return request<T>(`${path}${queryText ? `?${queryText}` : ''}`, { signal })
}

export const crashApi = {
  overview(appId: string, filters: CrashQueryFilters, signal?: AbortSignal) {
    return requestJson<CrashOverviewResponse>(
      appId,
      `/api/v1/apps/${pathSegment(appId)}/crashes/overview`,
      filters,
      signal,
    )
  },

  trend(
    appId: string,
    filters: CrashQueryFilters,
    interval: 'hour' | 'day' = 'hour',
    signal?: AbortSignal,
  ) {
    return requestJson<CrashTrendResponse>(
      appId,
      `/api/v1/apps/${pathSegment(appId)}/crashes/trend`,
      filters,
      signal,
      { interval },
    )
  },

  issues(appId: string, filters: CrashQueryFilters, signal?: AbortSignal) {
    return requestJson<CrashIssueResponse>(
      appId,
      `/api/v1/apps/${pathSegment(appId)}/crashes/issues`,
      filters,
      signal,
    )
  },

  events(
    appId: string,
    fingerprint: string,
    filters: CrashQueryFilters,
    signal?: AbortSignal,
  ) {
    return requestJson<CrashEventListResponse>(
      appId,
      `/api/v1/apps/${pathSegment(appId)}/crashes/issues/${pathSegment(fingerprint)}/events`,
      filters,
      signal,
    )
  },

  event(appId: string, eventId: string, signal?: AbortSignal) {
    return requestJson<CrashEventDetailResponse>(
      appId,
      `/api/v1/apps/${pathSegment(appId)}/crashes/events/${pathSegment(eventId)}`,
      {},
      signal,
    )
  },
}

export function isAbortError(error: unknown): boolean {
  return sharedIsAbortError(error)
}

export function errorMessage(error: unknown): string {
  return sharedErrorMessage(error)
}
