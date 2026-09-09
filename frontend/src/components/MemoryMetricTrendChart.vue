<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { MemoryMetric, MemoryPercentile, MemoryTrendPoint } from '../types/memory'

echarts.use([LineChart, GridComponent, TooltipComponent, CanvasRenderer])
const props = defineProps<{ metric: MemoryMetric; percentile: MemoryPercentile; points: MemoryTrendPoint[] }>()
const element = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null

const names: Record<MemoryMetric, string> = { pss: 'PSS', vss: 'VSS', java_heap: 'Java 堆' }
const fields: Record<MemoryPercentile, keyof MemoryTrendPoint> = {
  p50: 'p50Bytes', p90: 'p90Bytes', p95: 'p95Bytes', p99: 'p99Bytes',
}

/** 服务端按 UTC 分桶，横轴也固定使用 UTC，避免浏览器时区改变桶标签。 */
function formatUtcDate(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'UTC', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(date)
}

function render(): void {
  if (!chart) return
  const field = fields[props.percentile]
  chart.setOption({
    animationDuration: 300,
    grid: { top: 30, right: 28, bottom: 38, left: 68 },
    tooltip: { trigger: 'axis', valueFormatter: (value: unknown) => value === null || value === undefined ? '—' : `${(Number(value) / 1_048_576).toFixed(2)} MiB` },
    xAxis: { type: 'category', boundaryGap: false, data: props.points.map((point) => formatUtcDate(point.bucketStart)), axisLabel: { color: '#748398', fontSize: 10 } },
    yAxis: { type: 'value', name: 'MiB', axisLabel: { color: '#748398', fontSize: 10, formatter: (value: number) => (value / 1_048_576).toFixed(1) }, splitLine: { lineStyle: { color: '#e8edf4' } } },
    series: [{ name: `${names[props.metric]} ${props.percentile.toUpperCase()}`, type: 'line', connectNulls: false, showSymbol: true, symbolSize: 5, data: props.points.map((point) => point[field] ?? null) }],
  }, true)
}

function resize(): void { chart?.resize() }
onMounted(async () => { await nextTick(); if (element.value) { chart = echarts.init(element.value); render(); window.addEventListener('resize', resize) } })
watch(() => [props.metric, props.percentile, props.points], render, { deep: true })
onBeforeUnmount(() => { window.removeEventListener('resize', resize); chart?.dispose(); chart = null })
</script>

<template><div ref="element" class="chart metric-chart" role="img" :aria-label="`${metric} ${percentile} 内存趋势图，单位 MiB`" /></template>
