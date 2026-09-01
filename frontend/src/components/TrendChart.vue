<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { CrashTrendPoint } from '../types/crash'
import { formatDate } from '../utils/format'

echarts.use([LineChart, GridComponent, TooltipComponent, CanvasRenderer])

const props = defineProps<{
  points: CrashTrendPoint[]
  interval: string
}>()

const element = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null

function renderChart() {
  if (!chart) {
    return
  }
  const labels = props.points.map((point) => formatDate(point.bucketStart))
  const values = props.points.map((point) => point.stats.crashEvents)
  chart.setOption({
    animationDuration: 420,
    grid: { top: 18, right: 26, bottom: 34, left: 48 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: '#16223a',
      borderColor: '#385174',
      textStyle: { color: '#e8eef9' },
      formatter: (params: unknown) => {
        const item = Array.isArray(params) ? params[0] as { dataIndex: number; axisValue: string } : null
        return item ? `${item.axisValue}<br/>Crash 事件：${values[item.dataIndex] ?? 0}` : ''
      },
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: labels,
      axisLine: { lineStyle: { color: '#304462' } },
      axisLabel: { color: '#7e93b4', fontSize: 10 },
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      splitLine: { lineStyle: { color: 'rgba(48, 68, 98, 0.45)' } },
      axisLabel: { color: '#7e93b4', fontSize: 10 },
    },
    series: [{
      name: 'Crash 事件',
      type: 'line',
      smooth: true,
      symbol: 'circle',
      symbolSize: 6,
      data: values,
      lineStyle: { color: '#65a7ff', width: 3 },
      itemStyle: { color: '#8cc5ff', borderColor: '#0d172a', borderWidth: 2 },
      areaStyle: { color: 'rgba(101, 167, 255, 0.15)' },
    }],
  })
}

function resize() {
  chart?.resize()
}

onMounted(async () => {
  await nextTick()
  if (element.value) {
    chart = echarts.init(element.value)
    renderChart()
    window.addEventListener('resize', resize)
  }
})

watch(() => [props.points, props.interval], renderChart, { deep: true })

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div ref="element" class="chart" aria-label="Crash 趋势图" />
</template>
