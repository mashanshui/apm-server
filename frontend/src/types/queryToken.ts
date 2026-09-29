/** 查询 Token 列表中的公开元数据，绝不包含完整秘密。 */
export interface QueryTokenMetadata {
  id: string
  name: string
  displayPrefix: string
  scope: 'apm:read'
  createdBy: string
  createdAt: string
  expiresAt: string
  revokedAt: string | null
  status: 'ACTIVE' | 'EXPIRED' | 'REVOKED'
}

/** 创建响应是完整值唯一的来源。 */
export interface QueryTokenCreated {
  metadata: QueryTokenMetadata
  token: string
}

/** 服务端分页元数据。 */
export interface QueryTokenPage {
  items: QueryTokenMetadata[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

/** 创建时只允许名称和期限。 */
export interface QueryTokenCreateRequest {
  name: string
  expiresInDays: 30 | 90 | 365
}
