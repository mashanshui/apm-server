import type {
  CrashEventDetailResponse,
  CrashEventListResponse,
  CrashIssueResponse,
  CrashOverviewResponse,
  CrashQueryFilters,
  CrashTrendResponse,
} from '../types/crash'
import { ApiError, errorMessage as sharedErrorMessage, isAbortError as sharedIsAbortError, pathSegment, requestJson as request } from './http'
import { validDiagnostics } from './crashDiagnostics'

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

  async issues(appId: string, filters: CrashQueryFilters, signal?: AbortSignal) {
    const result = await requestJson<CrashIssueResponse>(
      appId,
      `/api/v1/apps/${pathSegment(appId)}/crashes/issues`,
      filters,
      signal,
    )
    if (!Array.isArray(result.issues) || !result.issues.every(item => validGrouping(item.groupingKind) && (item.groupingKind !== 'temporary' || Boolean(item.buildId)))) throw protocolError()
    return result
  },

  async events(
    appId: string,
    fingerprint: string,
    filters: CrashQueryFilters,
    signal?: AbortSignal,
  ) {
    const result = await requestJson<CrashEventListResponse>(
      appId,
      `/api/v1/apps/${pathSegment(appId)}/crashes/issues/${pathSegment(fingerprint)}/events`,
      filters,
      signal,
    )
    if (!Array.isArray(result.events) || !result.events.every(item => validGrouping(item.groupingKind) && validStatus(item.symbolicationStatus))) throw protocolError()
    return result
  },

  async event(appId: string, eventId: string, signal?: AbortSignal) {
    const result = await requestJson<CrashEventDetailResponse>(
      appId,
      `/api/v1/apps/${pathSegment(appId)}/crashes/events/${pathSegment(eventId)}`,
      {},
      signal,
    )
    return requireCurrentCrash(result)
  },
}

export function isAbortError(error: unknown): boolean {
  return sharedIsAbortError(error)
}

export function errorMessage(error: unknown): string {
  return sharedErrorMessage(error)
}

/** 不对旧响应作字段转换，协调切换错误直接显示为协议错误。 */
function protocolError(): Error { return new ApiError({ status: 502, code: 'PROTOCOL_INVALID', message: '协议错误：服务端 Crash 契约不受支持，请核对组件版本。' }) }
/** 限定当前归组类别。 */
function validGrouping(value: unknown): boolean { return value === 'temporary' || value === 'resolved' }
/** 限定当前持久化三态。 */
function validStatus(value: unknown): boolean { return ['pending', 'failed', 'symbolicated'].includes(String(value)) }
/** 验证单份载荷及实际来源，拒绝旧双份详情。 */
export function requireCurrentCrash(value: CrashEventDetailResponse): CrashEventDetailResponse {
  if (!value || 'rawCrash' in value || 'symbolicatedStackText' in value || !validStatus(value.symbolicationStatus)
      || !validGrouping(value.groupingKind) || typeof value.crashKind !== 'string' || typeof value.fatal !== 'boolean') throw protocolError()
  if (value.symbolicationStatus === 'symbolicated') {
    if (value.groupingKind !== 'resolved' || value.stack?.format !== 'retrace_text' || typeof value.stack.content !== 'string' || !value.stack.content.trim()
        || !value.symbolFileId || !Number.isInteger(value.symbolFileRevision) || (value.symbolFileRevision ?? 0) < 1
        || !/^[a-f0-9]{64}$/.test(value.symbolFileSha256 ?? '') || !value.processedAt) throw protocolError()
  } else if (value.groupingKind !== 'temporary' || value.stack?.format !== 'raw_chain'
      || !Array.isArray(value.stack.content?.throwableChain) || !value.stack.content.throwableChain.length
      || 'diagnostics' in value.stack.content) throw protocolError()
  if (!validDiagnostics(value.diagnostics)) throw protocolError()
  return value
}
