export type MemoryMetric = 'pss' | 'vss' | 'java_heap'
export type MemoryInterval = 'hour' | 'day'
export type MemoryPercentile = 'p50' | 'p90' | 'p95' | 'p99'
export type MemoryStatus = 'ok' | 'no_data' | string

export interface MemoryMetricStats {
  sampleCount: number
  averageBytes: number | null
  p50Bytes: number | null
  p90Bytes: number | null
  p95Bytes: number | null
  p99Bytes: number | null
  status: MemoryStatus
}

export interface MemoryMetricsSummaryResponse {
  appId: string
  from: string
  to: string
  pss: MemoryMetricStats
  vss: MemoryMetricStats
  javaHeap: MemoryMetricStats
  status: MemoryStatus
  dataSource: string
}

export interface MemoryTrendPoint {
  bucketStart: string
  bucketEnd: string
  sampleCount: number
  averageBytes: number | null
  p50Bytes: number | null
  p90Bytes: number | null
  p95Bytes: number | null
  p99Bytes: number | null
  status: MemoryStatus
}

export interface MemoryTrendResponse {
  appId: string
  from: string
  to: string
  metric: MemoryMetric
  interval: MemoryInterval
  points: MemoryTrendPoint[]
  status: MemoryStatus
  dataSource: string
}

export interface MemoryMetricQueryFilters {
  from?: string
  to?: string
  appVersion?: string
  osVersion?: string
  deviceModel?: string
  processName?: string
  scene?: string
  foreground?: boolean
  limit?: number
  timeoutMs?: number
}

export interface MemoryMetricFilterForm {
  from: string
  to: string
  appVersion: string
  osVersion: string
  deviceModel: string
  processName: string
  scene: string
  foreground: '' | 'true' | 'false'
  metric: MemoryMetric
  interval: MemoryInterval
  percentile: MemoryPercentile
}
