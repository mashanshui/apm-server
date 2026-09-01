import type { JankMetric, MetricTrendPoint } from '../types/jank'

const fields = ['average', 'p50', 'p90', 'p99'] as const
const labels: Record<typeof fields[number], string> = { average: '平均值', p50: 'P50', p90: 'P90', p99: 'P99' }

export interface JankMetricChartSeries {
  name: string
  data: (number | null)[]
}

export function buildMetricChartData(metric: JankMetric, points: MetricTrendPoint[]): {
  buckets: string[]
  series: JankMetricChartSeries[]
} {
  const buckets = [...new Set(points.map((point) => point.bucketStart))].sort()
  const versions = [...new Set(points.map((point) => point.algorithmVersion))].sort()
  const series = versions.flatMap((version) => fields.map((field) => ({
    name: `${version} · ${labels[field]}`,
    data: buckets.map((bucket) => {
      const point = points.find((item) => item.bucketStart === bucket && item.algorithmVersion === version)
      if (!point) return null
      if (metric === 'fps') return field === 'average' ? point.averageFps : point[`${field}Fps`]
      return field === 'average' ? point.averageSecondsPerHour : point[`${field}SecondsPerHour`]
    }),
  })))
  return { buckets, series }
}
