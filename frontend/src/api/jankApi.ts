import type {
  FpsMetricsResponse,
  JankDimension,
  JankEventDetailResponse,
  JankEventListResponse,
  JankInterval,
  JankIssueResponse,
  JankMetric,
  JankMetricQueryFilters,
  JankOverviewResponse,
  JankQueryFilters,
  JankTrendResponse,
  MetricDimensionsResponse,
  MetricTrendResponse,
  SuspensionRateResponse,
} from '../types/jank'
import {
  ApiError,
  errorMessage as sharedErrorMessage,
  isAbortError as sharedIsAbortError,
  pathSegment,
  requestJson as request,
} from './http'

export { ApiError as JankApiError }

const commonQueryKeys = [
  'from',
  'to',
  'appVersion',
  'channel',
  'environment',
  'osVersion',
  'deviceModel',
  'scene',
  'algorithmVersion',
  'limit',
  'timeoutMs',
] as const

const issueQueryKeys = [...commonQueryKeys, 'fingerprint', 'cursor'] as const

function appendQueryValues<T extends object>(
  params: URLSearchParams,
  filters: T,
  keys: readonly string[],
): void {
  const values = filters as Record<string, unknown>
  for (const key of keys) {
    const value = values[key]
    if (value !== undefined && value !== null && value !== '') {
      params.set(key, String(value))
    }
  }
}

export function buildJankQueryParams(filters: JankQueryFilters = {}): URLSearchParams {
  const params = new URLSearchParams()
  appendQueryValues(params, filters, issueQueryKeys)
  return params
}

export function buildJankMetricQueryParams(filters: JankMetricQueryFilters = {}): URLSearchParams {
  const params = new URLSearchParams()
  appendQueryValues(params, filters, commonQueryKeys)
  return params
}

async function query<T>(
  path: string,
  params: URLSearchParams,
  signal?: AbortSignal,
): Promise<T> {
  const queryText = params.toString()
  return request<T>(`${path}${queryText ? `?${queryText}` : ''}`, { signal })
}

function metricTrendParams(
  metric: JankMetric,
  interval: JankInterval,
  filters: JankMetricQueryFilters,
): URLSearchParams {
  if (metric === 'suspension_rate' && interval !== 'day') {
    throw new ApiError({
      status: 400,
      code: 'INVALID_INTERVAL',
      message: '设备日挂起率仅支持 UTC day 粒度',
      retryable: false,
    })
  }
  const params = buildJankMetricQueryParams(filters)
  params.set('metric', metric)
  params.set('interval', interval)
  return params
}

export const jankApi = {
  overview(appId: string, filters: JankQueryFilters, signal?: AbortSignal) {
    return query<JankOverviewResponse>(
      `/api/v1/apps/${pathSegment(appId)}/janks/overview`,
      buildJankQueryParams(filters),
      signal,
    )
  },

  trend(
    appId: string,
    filters: JankQueryFilters,
    interval: JankInterval = 'hour',
    signal?: AbortSignal,
  ) {
    const params = buildJankQueryParams(filters)
    params.set('interval', interval)
    return query<JankTrendResponse>(
      `/api/v1/apps/${pathSegment(appId)}/janks/trend`, params, signal,
    )
  },

  issues(appId: string, filters: JankQueryFilters, signal?: AbortSignal) {
    return query<JankIssueResponse>(
      `/api/v1/apps/${pathSegment(appId)}/janks/issues`,
      buildJankQueryParams(filters),
      signal,
    )
  },

  events(appId: string, fingerprint: string, filters: JankQueryFilters, signal?: AbortSignal) {
    return query<JankEventListResponse>(
      `/api/v1/apps/${pathSegment(appId)}/janks/issues/${pathSegment(fingerprint)}/events`,
      buildJankQueryParams(filters),
      signal,
    )
  },

  event(appId: string, eventId: string, signal?: AbortSignal) {
    return query<JankEventDetailResponse>(
      `/api/v1/apps/${pathSegment(appId)}/janks/events/${pathSegment(eventId)}`,
      new URLSearchParams(),
      signal,
    )
  },

  fps(appId: string, filters: JankMetricQueryFilters, signal?: AbortSignal) {
    return query<FpsMetricsResponse>(
      `/api/v1/apps/${pathSegment(appId)}/jank-metrics/fps`,
      buildJankMetricQueryParams(filters),
      signal,
    )
  },

  suspensionRate(appId: string, filters: JankMetricQueryFilters, signal?: AbortSignal) {
    return query<SuspensionRateResponse>(
      `/api/v1/apps/${pathSegment(appId)}/jank-metrics/suspension-rate`,
      buildJankMetricQueryParams(filters),
      signal,
    )
  },

  dimensions(
    appId: string,
    metric: JankMetric,
    dimension: JankDimension,
    filters: JankMetricQueryFilters,
    signal?: AbortSignal,
  ) {
    if (metric === 'suspension_rate' && dimension === 'scene') {
      throw new ApiError({
        status: 400,
        code: 'INVALID_DIMENSION',
        message: '设备日挂起率不支持 scene 维度',
        retryable: false,
      })
    }
    const params = buildJankMetricQueryParams(filters)
    params.set('metric', metric)
    params.set('dimension', dimension)
    return query<MetricDimensionsResponse>(
      `/api/v1/apps/${pathSegment(appId)}/jank-metrics/dimensions`, params, signal,
    )
  },

  metricTrend(
    appId: string,
    metric: JankMetric,
    interval: JankInterval,
    filters: JankMetricQueryFilters,
    signal?: AbortSignal,
  ) {
    return query<MetricTrendResponse>(
      `/api/v1/apps/${pathSegment(appId)}/jank-metrics/trend`,
      metricTrendParams(metric, interval, filters),
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
