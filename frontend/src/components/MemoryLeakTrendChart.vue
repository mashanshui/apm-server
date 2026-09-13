<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { MemoryLeakTrendMetric, MemoryLeakTrendPoint } from '../types/memoryLeak'
import { formatDateTime } from '../utils/format'

echarts.use([LineChart, GridComponent, TooltipComponent, CanvasRenderer])

const props = defineProps<{ points: MemoryLeakTrendPoint[]; metric: MemoryLeakTrendMetric }>()
const element = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null

function render(): void {
  if (!chart) return
  const metricLabel = props.metric === 'occurrences' ? '发生次数' : '影响设备数'
  chart.setOption({
    animationDuration: 260,
    grid: { top: 22, right: 28, bottom: 52, left: 52 },
    tooltip: {
      trigger: 'axis',
      formatter: (params: unknown) => {
        const point = Array.isArray(params) ? (params[0] as { dataIndex?: number }) : {}
        const item = point.dataIndex === undefined ? undefined : props.points[point.dataIndex]
        return item ? `${formatDateTime(item.bucketStart)}<br/>${metricLabel}：${props.metric === 'occurrences' ? item.occurrenceCount : item.affectedDeviceCount}` : ''
      },
    },
    xAxis: {
      type: 'category', boundaryGap: false,
      data: props.points.map((point) => formatDateTime(point.bucketStart)),
      axisLabel: { color: '#748398', fontSize: 10, hideOverlap: true },
    },
    yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: '#e8edf4' } }, axisLabel: { color: '#748398', fontSize: 10 } },
    series: [{ name: metricLabel, type: 'line', smooth: true, data: props.points.map((point) => props.metric === 'occurrences' ? point.occurrenceCount : point.affectedDeviceCount), lineStyle: { color: '#2768c7', width: 3 }, itemStyle: { color: '#2768c7' }, areaStyle: { color: 'rgba(39,104,199,.08)' } }],
  }, true)
}

function resize(): void { chart?.resize() }
onMounted(async () => { await nextTick(); if (element.value) { chart = echarts.init(element.value); render(); window.addEventListener('resize', resize) } })
watch(() => [props.points, props.metric], render, { deep: true })
onBeforeUnmount(() => { window.removeEventListener('resize', resize); chart?.dispose(); chart = null })
</script>

<template><div ref="element" class="chart" role="img" aria-label="内存泄漏报告趋势图" /></template>
