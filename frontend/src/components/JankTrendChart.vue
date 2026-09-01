<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { JankTrendPoint } from '../types/jank'
import { formatDate } from '../utils/format'

echarts.use([LineChart, GridComponent, TooltipComponent, CanvasRenderer])

const props = defineProps<{ points: JankTrendPoint[]; interval: string }>()
const element = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null

function render(): void {
  if (!chart) return
  const labels = props.points.map((point) => formatDate(point.bucketStart))
  const events = props.points.map((point) => point.stats.jankEvents)
  const devices = props.points.map((point) => point.stats.affectedDevices)
  chart.setOption({
    animationDuration: 360,
    grid: { top: 22, right: 28, bottom: 36, left: 52 },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', boundaryGap: false, data: labels, axisLabel: { color: '#748398', fontSize: 10 } },
    yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: '#e8edf4' } }, axisLabel: { color: '#748398', fontSize: 10 } },
    series: [
      { name: '卡顿事件', type: 'line', smooth: true, data: events, lineStyle: { color: '#2768c7', width: 3 }, itemStyle: { color: '#2768c7' } },
      { name: '受影响设备', type: 'line', smooth: true, data: devices, lineStyle: { color: '#24a476', width: 2 }, itemStyle: { color: '#24a476' } },
    ],
  }, true)
}

function resize(): void { chart?.resize() }
onMounted(async () => { await nextTick(); if (element.value) { chart = echarts.init(element.value); render(); window.addEventListener('resize', resize) } })
watch(() => [props.points, props.interval], render, { deep: true })
onBeforeUnmount(() => { window.removeEventListener('resize', resize); chart?.dispose(); chart = null })
</script>

<template><div ref="element" class="chart" role="img" aria-label="卡顿事件与受影响设备趋势图" /></template>
