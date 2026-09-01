<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { JankMetric, MetricTrendPoint } from '../types/jank'
import { formatDate } from '../utils/format'
import { buildMetricChartData } from '../utils/jankMetricChart'

echarts.use([LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])
const props = defineProps<{ metric: JankMetric; points: MetricTrendPoint[] }>()
const element = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null
function render(): void {
  if (!chart) return
  const data = buildMetricChartData(props.metric, props.points)
  const series = data.series.map((item) => ({
    name: item.name,
    type: 'line' as const,
    connectNulls: false,
    showSymbol: true,
    symbolSize: 5,
    data: item.data,
  }))
  chart.setOption({
    animationDuration: 360, grid: { top: 62, right: 28, bottom: 38, left: 58 }, tooltip: { trigger: 'axis' },
    legend: { type: 'scroll', top: 8, textStyle: { color: '#516175', fontSize: 10 } },
    xAxis: { type: 'category', boundaryGap: false, data: data.buckets.map(formatDate), axisLabel: { color: '#748398', fontSize: 10 } },
    yAxis: { type: 'value', name: props.metric === 'fps' ? '帧/秒' : '秒/小时前台时长', splitLine: { lineStyle: { color: '#e8edf4' } }, axisLabel: { color: '#748398', fontSize: 10 } },
    series,
  }, true)
}
function resize(): void { chart?.resize() }
onMounted(async () => { await nextTick(); if (element.value) { chart = echarts.init(element.value); render(); window.addEventListener('resize', resize) } })
watch(() => [props.metric, props.points], render, { deep: true })
onBeforeUnmount(() => { window.removeEventListener('resize', resize); chart?.dispose(); chart = null })
</script>

<template><div ref="element" class="chart metric-chart" role="img" :aria-label="`${metric === 'fps' ? 'FPS' : '设备日挂起率'}多算法版本平均值和分位数趋势图`" /></template>
