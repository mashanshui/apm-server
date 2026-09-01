import { describe, expect, it } from 'vitest'
import type { MetricTrendPoint } from '../types/jank'
import { buildMetricChartData } from './jankMetricChart'

function fpsPoint(bucketStart: string, version: string, average: number | null): MetricTrendPoint {
  return {
    bucketStart, bucketEnd: bucketStart, algorithmVersion: version, totalRecords: 1, validRecords: average === null ? 0 : 1,
    averageFps: average, p50Fps: average, p90Fps: average, p99Fps: average,
    averageSecondsPerHour: null, p50SecondsPerHour: null, p90SecondsPerHour: null, p99SecondsPerHour: null,
    status: average === null ? 'no_valid_data' : 'ok',
  }
}

describe('buildMetricChartData', () => {
  it('按算法版本隔离系列并以 null 保留缺失时间桶', () => {
    const first = '2026-08-15T10:00:00Z'
    const second = '2026-08-15T11:00:00Z'
    const data = buildMetricChartData('fps', [fpsPoint(first, 'fps-v1', 50), fpsPoint(second, 'fps-v2', 0)])
    expect(data.buckets).toEqual([first, second])
    expect(data.series.find((item) => item.name === 'fps-v1 · 平均值')?.data).toEqual([50, null])
    expect(data.series.find((item) => item.name === 'fps-v2 · 平均值')?.data).toEqual([null, 0])
  })
})
