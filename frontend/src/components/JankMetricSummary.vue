<script setup lang="ts">
import type { FpsMetricsResponse, SuspensionRateResponse } from '../types/jank'
import { formatNumber } from '../utils/format'

defineProps<{ metric: 'fps' | 'suspension_rate'; response: FpsMetricsResponse | SuspensionRateResponse }>()

function value(number: number | null, unit: string): string { return number === null ? '—' : `${formatNumber(number)} ${unit}` }
function statusText(status: string): string {
  if (status === 'no_data') return '当前范围无记录'
  if (status === 'no_valid_data') return '没有有效 FPS 记录'
  if (status === 'denominator_insufficient') return '前台时长分母不足'
  return status === 'ok' ? '数据正常' : status
}
</script>

<template>
  <div v-if="response.metrics.length === 0" class="empty-state"><p>{{ statusText(response.status) }}</p></div>
  <div v-else class="metric-algorithm-list">
    <section v-for="item in response.metrics" :key="item.algorithmVersion" class="metric-algorithm-block">
      <div class="metric-algorithm-heading"><div><h3>算法 {{ item.algorithmVersion }}</h3><p>{{ statusText(item.status) }}</p></div><span class="badge">{{ item.totalRecords }} 条原始记录</span></div>
      <div v-if="metric === 'fps' && 'averageFps' in item" class="stats-grid metric-stats-grid">
        <div class="metric-stat"><span>平均值</span><strong>{{ value(item.averageFps, '帧/秒') }}</strong></div>
        <div class="metric-stat"><span>P50</span><strong>{{ value(item.p50Fps, '帧/秒') }}</strong></div>
        <div class="metric-stat"><span>P90</span><strong>{{ value(item.p90Fps, '帧/秒') }}</strong></div>
        <div class="metric-stat"><span>P99</span><strong>{{ value(item.p99Fps, '帧/秒') }}</strong></div>
        <div class="metric-stat"><span>总记录</span><strong>{{ formatNumber(item.totalRecords) }}</strong></div>
        <div class="metric-stat"><span>有效 FPS 记录</span><strong>{{ formatNumber(item.validRecords) }}</strong></div>
      </div>
      <div v-else-if="metric === 'suspension_rate' && 'averageSecondsPerHour' in item" class="stats-grid metric-stats-grid">
        <div class="metric-stat"><span>平均值</span><strong>{{ value(item.averageSecondsPerHour, '秒/小时前台时长') }}</strong></div>
        <div class="metric-stat"><span>P50</span><strong>{{ value(item.p50SecondsPerHour, '秒/小时前台时长') }}</strong></div>
        <div class="metric-stat"><span>P90</span><strong>{{ value(item.p90SecondsPerHour, '秒/小时前台时长') }}</strong></div>
        <div class="metric-stat"><span>P99</span><strong>{{ value(item.p99SecondsPerHour, '秒/小时前台时长') }}</strong></div>
        <div class="metric-stat"><span>原始区间</span><strong>{{ formatNumber(item.totalRecords) }}</strong></div>
        <div class="metric-stat"><span>有效设备日</span><strong>{{ formatNumber(item.validDeviceDayRecords) }}</strong></div>
      </div>
    </section>
  </div>
</template>
