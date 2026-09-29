import { pathSegment, requestJson } from './http'
import type { QueryTokenCreateRequest, QueryTokenCreated, QueryTokenPage } from '../types/queryToken'

/** 复用 Session/CSRF HTTP 客户端的查询 Token 管理 API。 */
export const queryTokenApi = {
  /** 只读取前缀及管理元数据。 */
  list(appId: string, page = 0, size = 20, signal?: AbortSignal) {
    return requestJson<QueryTokenPage>(
      `/api/v1/apps/${pathSegment(appId)}/query-tokens?page=${page}&size=${size}`,
      { signal },
    )
  },
  /** 创建操作不自动重试，完整值仅由本次响应返回。 */
  create(appId: string, request: QueryTokenCreateRequest, signal?: AbortSignal) {
    return requestJson<QueryTokenCreated>(`/api/v1/apps/${pathSegment(appId)}/query-tokens`, {
      method: 'POST', body: JSON.stringify(request), signal,
    })
  },
  /** 撤销操作按服务端同应用幂等语义执行。 */
  revoke(appId: string, tokenId: string, signal?: AbortSignal) {
    return requestJson<void>(
      `/api/v1/apps/${pathSegment(appId)}/query-tokens/${pathSegment(tokenId)}`,
      { method: 'DELETE', signal },
    )
  },
}
