export type AppRole = 'OWNER' | 'ADMIN' | 'DEVELOPER' | 'VIEWER'

export interface App {
  appId: string
  name: string
  description: string | null
  packageName: string
  role: AppRole
  createdAt: string
  updatedAt: string
}

export interface AppCreateRequest {
  name?: string
  description?: string
  packageName: string
}

/** PATCH 仅更新出现的字段；描述显式 null 表示清空。 */
export interface AppUpdateRequest {
  /** 未提交时保留名称，提交值必须非空。 */
  name?: string
  /** 未提交保留，null 或空白清空。 */
  description?: string | null
}

export interface AppIngestCredential {
  appId: string
  packageName: string
  appKey: string
}
