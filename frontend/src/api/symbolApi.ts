import type { SymbolFileMetadata, SymbolFilePage, SymbolicationProgress } from '../types/symbol'
import { pathSegment, requestJson } from './http'

/** 符号表网页管理 API。 */
export const symbolApi = {
  /** 仅查询本构建的管理记录，不扫描 Crash。 */
  progress(appId: string, symbolId: string, signal?: AbortSignal) {
    return requestJson<SymbolicationProgress>(`/api/v1/apps/${pathSegment(appId)}/symbols/${pathSegment(symbolId)}/crash-symbolication`, { signal })
  },
  /** 删除时锁定用户确认的 revision。 */
  remove(appId: string, symbolId: string, expectedRevision: number, signal?: AbortSignal) {
    return requestJson<void>(`/api/v1/apps/${pathSegment(appId)}/symbols/${pathSegment(symbolId)}?expectedRevision=${expectedRevision}`, { method: 'DELETE', signal })
  },
  /** 查询当前应用的 mapping 元数据。 */
  list(appId: string, options: { buildId?: string; cursor?: string; limit?: number } = {}, signal?: AbortSignal) {
    const params = new URLSearchParams()
    if (options.buildId) params.set('buildId', options.buildId)
    if (options.cursor) params.set('cursor', options.cursor)
    if (options.limit) params.set('limit', String(options.limit))
    const suffix = params.toString() ? `?${params.toString()}` : ''
    return requestJson<SymbolFilePage>(`/api/v1/apps/${pathSegment(appId)}/symbols${suffix}`, { signal })
  },

  /** 首次上传单份 mapping。 */
  upload(appId: string, buildId: string, file: File, signal?: AbortSignal) {
    const form = new FormData()
    form.append('buildId', buildId)
    form.append('file', file)
    return requestJson<SymbolFileMetadata>(`/api/v1/apps/${pathSegment(appId)}/symbols`, {
      method: 'POST',
      body: form,
      signal,
    })
  },

  /** 管理员确认替换当前 mapping。 */
  replace(appId: string, symbolId: string, expectedRevision: number, file: File, signal?: AbortSignal) {
    const form = new FormData()
    form.append('expectedRevision', String(expectedRevision))
    form.append('file', file)
    return requestJson<SymbolFileMetadata>(
      `/api/v1/apps/${pathSegment(appId)}/symbols/${pathSegment(symbolId)}`,
      { method: 'PUT', body: form, signal },
    )
  },
}
