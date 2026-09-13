export type MemoryLeakInterval = '5m' | 'hour' | 'day'
export type MemoryLeakTrendMetric = 'occurrences' | 'affectedDevices'
export type MemoryLeakIssueSort = 'occurrences' | 'affectedDevices' | 'lastOccurredAt'
export type MemoryLeakOrder = 'asc' | 'desc'
export type MemoryLeakStatus = 'ok' | 'no_data' | string

export interface MemoryLeakPathNode {
  reference: string
  referenceType: string
  declaredClass?: string | null
}

export interface MemoryLeakIssue {
  signature: string
  leakClass: string | null
  leakReason: string | null
  gcRoot: string | null
  path: MemoryLeakPathNode[]
  lastOccurredAt: string | null
  occurrences: number
  occurrenceRatio: number | null
  affectedDevices: number
  deviceRatio: number | null
  versions: string[]
}

export interface MemoryLeakIssuesResponse {
  appId: string
  from: string
  to: string
  total: number
  totalOccurrences: number
  totalAffectedDevices: number
  page: number
  pageSize: number
  items: MemoryLeakIssue[]
  status: MemoryLeakStatus
  dataSource: string
}

export interface MemoryLeakTrendPoint {
  bucketStart: string
  occurrenceCount: number
  affectedDeviceCount: number
}

export interface MemoryLeakTrendResponse {
  appId: string
  from: string
  to: string
  interval: MemoryLeakInterval
  points: MemoryLeakTrendPoint[]
  status: MemoryLeakStatus
  dataSource: string
}

export interface MemoryLeakQueryFilters {
  from?: string
  to?: string
  appVersion?: string
  deviceModel?: string
  processName?: string
  scene?: string
  manufacturer?: string
  sdkInt?: string | number
  dumpReason?: string
  anonymousDeviceId?: string
  signature?: string
  keyword?: string
  page?: number
  pageSize?: number
  sort?: MemoryLeakIssueSort
  order?: MemoryLeakOrder
  interval?: MemoryLeakInterval
}

export interface MemoryLeakFilterForm {
  from: string
  to: string
  appVersion: string
  deviceModel: string
  processName: string
  scene: string
  manufacturer: string
  sdkInt: string
  dumpReason: string
  anonymousDeviceId: string
  signature: string
  keyword: string
  page: number
  pageSize: number
  sort: MemoryLeakIssueSort
  order: MemoryLeakOrder
  interval: MemoryLeakInterval
  trendMetric: MemoryLeakTrendMetric
}
