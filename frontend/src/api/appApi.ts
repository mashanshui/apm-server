import type { App, AppCreateRequest, AppIngestCredential, AppUpdateRequest } from '../types/app'
import { pathSegment, requestJson } from './http'

export const appApi = {
  list(query = '') {
    const params = new URLSearchParams()
    if (query.trim()) params.set('query', query.trim())
    const suffix = params.toString() ? `?${params.toString()}` : ''
    return requestJson<App[]>(`/api/v1/apps${suffix}`)
  },
  get(appId: string) {
    return requestJson<App>(`/api/v1/apps/${pathSegment(appId)}`)
  },
  create(request: AppCreateRequest) {
    return requestJson<App>('/api/v1/apps', {
      method: 'POST',
      body: JSON.stringify(request),
    })
  },
  update(appId: string, request: AppUpdateRequest) {
    return requestJson<App>(`/api/v1/apps/${pathSegment(appId)}`, {
      method: 'PATCH',
      body: JSON.stringify(request),
    })
  },
  getIngestCredential(appId: string, signal?: AbortSignal) {
    return requestJson<AppIngestCredential>(
      `/api/v1/apps/${pathSegment(appId)}/ingest-credential`,
      { signal },
    )
  },
}
