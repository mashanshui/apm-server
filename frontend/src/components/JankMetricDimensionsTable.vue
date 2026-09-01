<script setup lang="ts">
import type { JankDimension, JankMetric, MetricDimensionPoint } from '../types/jank'
import { formatNumber } from '../utils/format'
defineProps<{ metric: JankMetric; dimension: JankDimension; points: MetricDimensionPoint[] }>()
function value(number: number | null, unit: string): string { return number === null ? '—' : `${formatNumber(number)} ${unit}` }
</script>

<template>
  <div v-if="points.length === 0" class="empty-state"><p>当前范围没有多维分析数据</p></div>
  <div v-else class="table-wrap jank-wide-table"><table class="data-table">
    <thead><tr><th>{{ dimension }}</th><th>算法版本</th><th>总记录</th><th>{{ metric === 'fps' ? '有效 FPS 记录' : '有效设备日' }}</th><th>平均值</th><th>P50</th><th>P90</th><th>P99</th><th>状态</th></tr></thead>
    <tbody><tr v-for="(point, index) in points" :key="`${point.dimensionValue}-${point.algorithmVersion}-${index}`">
      <td>{{ point.dimensionValue || '（空值）' }}</td><td>{{ point.algorithmVersion }}</td><td>{{ formatNumber(point.totalRecords) }}</td><td>{{ formatNumber(metric === 'fps' ? point.validRecords : point.validDeviceDayRecords) }}</td>
      <template v-if="metric === 'fps'"><td>{{ value(point.averageFps, '帧/秒') }}</td><td>{{ value(point.p50Fps, '帧/秒') }}</td><td>{{ value(point.p90Fps, '帧/秒') }}</td><td>{{ value(point.p99Fps, '帧/秒') }}</td></template>
      <template v-else><td>{{ value(point.averageSecondsPerHour, '秒/小时') }}</td><td>{{ value(point.p50SecondsPerHour, '秒/小时') }}</td><td>{{ value(point.p90SecondsPerHour, '秒/小时') }}</td><td>{{ value(point.p99SecondsPerHour, '秒/小时') }}</td></template>
      <td><span class="badge">{{ point.status }}</span></td>
    </tr></tbody>
  </table></div>
</template>
