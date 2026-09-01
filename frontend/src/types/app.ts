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

export interface AppUpdateRequest {
  name: string
  description: string
}

export interface AppIngestCredential {
  appId: string
  packageName: string
  appKey: string
}
