export type JankStatus = 'ok' | 'no_data' | 'no_valid_data' | 'denominator_insufficient' | string
export type JankInterval = 'hour' | 'day'
export type JankMetric = 'fps' | 'suspension_rate'
export type JankDimension =
  | 'appVersion'
  | 'channel'
  | 'environment'
  | 'osVersion'
  | 'deviceModel'
  | 'scene'
  | 'algorithmVersion'

export interface JankDurationPercentiles {
  p50Ms: number | null
  p90Ms: number | null
  p99Ms: number | null
}

export interface JankStats {
  jankEvents: number
  affectedSessions: number
  affectedDevices: number
  groupableEvents: number
  exactMessageDuration: JankDurationPercentiles
  status: JankStatus
}

export interface JankOverviewResponse {
  appId: string
  from: string
  to: string
  stats: JankStats
  dataSource: string
}

export interface JankTrendPoint {
  bucketStart: string
  bucketEnd: string
  stats: JankStats
}

export interface JankTrendResponse {
  appId: string
  from: string
  to: string
  interval: JankInterval | string
  points: JankTrendPoint[]
  status: JankStatus
  dataSource: string
}

export interface JankIssueSummary {
  fingerprint: string
  fingerprintVersion: string
  scene: string
  algorithmVersion: string
  eventCount: number
  affectedSessionCount: number
  affectedDeviceCount: number
  firstSeenAt: string
  lastSeenAt: string
  exactMessageDuration: JankDurationPercentiles
  estimatedStackDuration: JankDurationPercentiles
}

export interface JankIssueResponse {
  appId: string
  from: string
  to: string
  issues: JankIssueSummary[]
  nextCursor: string | null
  status: JankStatus
  dataSource: string
}

export interface JankEventSummary {
  eventId: string
  occurredAt: string
  appVersion: string
  versionCode: number | null
  buildId: string
  channel: string
  environment: string
  osVersion: string
  deviceModel: string
  sessionId: string | null
  anonymousDeviceId: string | null
  scene: string
  algorithmVersion: string
  fingerprint: string
  fingerprintVersion: string
  exactMessageDurationMs: number | null
  estimatedDurationMs: number | null
  estimatedUnattributedDurationMs: number | null
  coveredDurationMs: number | null
  uncoveredDurationMs: number | null
}

export interface JankEventListResponse {
  appId: string
  fingerprint: string
  from: string
  to: string
  events: JankEventSummary[]
  nextCursor: string | null
  status: JankStatus
  dataSource: string
}

export interface JankStackFrame {
  className: string
  methodName: string
  fileName: string | null
  lineNumber: number | null
  applicationFrame: boolean | null
}

export interface JankSample {
  offsetNs: number | null
  stackId: string
}

export interface JankPayload {
  scene: string
  algorithmVersion: string
  messageDurationNs: number | null
  thresholdNs: number | null
  samplingIntervalNs: number | null
  samples: JankSample[]
  stackDictionary: Record<string, JankStackFrame[]>
  expectedSampleCount: number | null
  parsedSampleCount: number | null
  missingSampleCount: number | null
}

export interface JankSampleSlice {
  startOffsetNs: number
  endOffsetNs: number
  stackId: string | null
  covered: boolean
  durationNs: number
}

export interface JankCallTreeNode {
  className: string
  methodName: string
  estimatedDurationNs: number
  estimatedUnattributedDurationNs: number
  children: JankCallTreeNode[]
}

export interface JankAnalysis {
  exactMessageDurationNs: number
  estimatedDurationNs: number
  estimatedUnattributedDurationNs: number
  coveredDurationNs: number
  uncoveredDurationNs: number
  sampleSlices: JankSampleSlice[]
  callTree: JankCallTreeNode[]
  stackDictionary: Record<string, JankStackFrame[]>
  expectedSampleCount: number
  parsedSampleCount: number
  missingSampleCount: number
  algorithmVersion: string
  warnings: string[]
}

export interface JankEventDetailResponse {
  appId: string
  eventId: string
  packageName: string
  occurredAt: string
  receivedAt: string
  sessionId: string | null
  anonymousDeviceId: string | null
  appVersion: string
  versionCode: number | null
  buildId: string
  channel: string
  environment: string
  osVersion: string
  deviceModel: string
  networkType: string | null
  scene: string
  algorithmVersion: string
  fingerprint: string
  fingerprintVersion: string
  jank: JankPayload
  analysis: JankAnalysis
}

export interface FpsMetricStats {
  algorithmVersion: string
  totalRecords: number
  validRecords: number
  averageFps: number | null
  p50Fps: number | null
  p90Fps: number | null
  p99Fps: number | null
  status: JankStatus
}

export interface FpsMetricsResponse {
  appId: string
  from: string
  to: string
  metrics: FpsMetricStats[]
  status: JankStatus
  dataSource: string
}

export interface SuspensionRateStats {
  algorithmVersion: string
  totalRecords: number
  validDeviceDayRecords: number
  averageSecondsPerHour: number | null
  p50SecondsPerHour: number | null
  p90SecondsPerHour: number | null
  p99SecondsPerHour: number | null
  status: JankStatus
}

export interface SuspensionRateResponse {
  appId: string
  from: string
  to: string
  metrics: SuspensionRateStats[]
  status: JankStatus
  dataSource: string
}

export interface MetricDimensionPoint {
  dimensionValue: string
  algorithmVersion: string
  totalRecords: number
  validRecords: number
  validDeviceDayRecords: number
  averageFps: number | null
  p50Fps: number | null
  p90Fps: number | null
  p99Fps: number | null
  averageSecondsPerHour: number | null
  p50SecondsPerHour: number | null
  p90SecondsPerHour: number | null
  p99SecondsPerHour: number | null
  status: JankStatus
}

export interface MetricDimensionsResponse {
  appId: string
  from: string
  to: string
  metric: JankMetric
  dimension: JankDimension
  points: MetricDimensionPoint[]
  status: JankStatus
  dataSource: string
}

export interface MetricTrendPoint {
  bucketStart: string
  bucketEnd: string
  algorithmVersion: string
  totalRecords: number
  validRecords: number
  averageFps: number | null
  p50Fps: number | null
  p90Fps: number | null
  p99Fps: number | null
  averageSecondsPerHour: number | null
  p50SecondsPerHour: number | null
  p90SecondsPerHour: number | null
  p99SecondsPerHour: number | null
  status: JankStatus
}

export interface MetricTrendResponse {
  appId: string
  from: string
  to: string
  metric: JankMetric
  interval: JankInterval
  points: MetricTrendPoint[]
  status: JankStatus
  dataSource: string
}

export interface JankQueryFilters {
  from?: string
  to?: string
  appVersion?: string
  channel?: string
  environment?: string
  osVersion?: string
  deviceModel?: string
  fingerprint?: string
  scene?: string
  algorithmVersion?: string
  limit?: number
  cursor?: string
  timeoutMs?: number
}

export type JankMetricQueryFilters = Omit<JankQueryFilters, 'fingerprint' | 'cursor'>

export interface JankFilterForm {
  from: string
  to: string
  appVersion: string
  channel: string
  environment: string
  osVersion: string
  deviceModel: string
  fingerprint: string
  scene: string
  algorithmVersion: string
  interval: JankInterval
}

export interface JankMetricFilterForm {
  from: string
  to: string
  appVersion: string
  channel: string
  environment: string
  osVersion: string
  deviceModel: string
  scene: string
  algorithmVersion: string
  metric: JankMetric
  interval: JankInterval
  dimension: JankDimension
}
