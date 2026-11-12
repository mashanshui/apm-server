import type { SymbolFileMetadata, SymbolFilePage, SymbolicationProgress, NativeExpected, NativeBatchResult } from '../types/symbol'
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
  list(appId: string, options: { type?: string; appVersion?: string; mappingScope?: string; buildId?: string; abi?: string; elfBuildId?: string; cursor?: string; limit?: number } = {}, signal?: AbortSignal) {
    const params = new URLSearchParams()
    for (const key of ['type', 'appVersion', 'mappingScope', 'abi', 'elfBuildId'] as const) {
      if (options[key]) params.set(key, options[key])
    }
    if (options.buildId) params.set('buildId', options.buildId)
    if (options.cursor) params.set('cursor', options.cursor)
    if (options.limit) params.set('limit', String(options.limit))
    const suffix = params.toString() ? `?${params.toString()}` : ''
    return requestJson<SymbolFilePage>(`/api/v1/apps/${pathSegment(appId)}/symbols${suffix}`, { signal })
  },

  /** 首次上传单份 mapping。 */
  upload(appId: string, appVersion: string, buildId: string | null, file: File, signal?: AbortSignal) {
    const form = new FormData()
    form.append('appVersion', appVersion)
    if (buildId?.trim()) form.append('buildId', buildId)
    form.append('file', file)
    return requestJson<SymbolFileMetadata>(`/api/v1/apps/${pathSegment(appId)}/symbols`, {
      method: 'POST',
      body: form,
      signal,
    })
  },

  /** SO 批次只携带版本和模块目标，确认必须覆盖全部候选。 */
  uploadNative(appId: string, appVersion: string, file: File, target: { abi?: string; elfBuildId?: string },
    expectedEntries?: NativeExpected[], signal?: AbortSignal) {
    const form = new FormData()
    form.append('appVersion', appVersion)
    form.append('file', file)
    if (target.abi && target.elfBuildId) {
      form.append('targetAbi', target.abi)
      form.append('targetElfBuildId', target.elfBuildId)
    }
    if (expectedEntries) form.append('expectedEntries', JSON.stringify(expectedEntries))
    return requestJson<NativeBatchResult>(`/api/v1/apps/${pathSegment(appId)}/symbols/native-batches`, {
      method: 'POST', body: form, signal,
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
