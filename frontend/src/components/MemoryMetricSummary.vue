<script setup lang="ts">
import type { MemoryMetric, MemoryMetricStats } from '../types/memory'

/** 当前指标及其运行峰值统计。 */
const props = defineProps<{ metric: MemoryMetric; stats: MemoryMetricStats }>()

/** 三项内存指标名称。 */
const labels: Record<MemoryMetric, string> = { pss: 'PSS', vss: 'VSS', java_heap: 'Java 堆' }
/** 服务端提供的五项统计卡片。 */
const cards = [
  { key: 'averageBytes', label: '平均值' },
  { key: 'p50Bytes', label: 'P50' },
  { key: 'p90Bytes', label: 'P90' },
  { key: 'p95Bytes', label: 'P95' },
  { key: 'p99Bytes', label: 'P99' },
] as const

/** 将有效字节数转换为 MiB，缺失保持占位。 */
function value(raw: number | null): string {
  return raw === null ? '—' : `${(raw / 1_048_576).toFixed(2)} MiB`
}
</script>

<template>
  <div v-if="stats.status === 'no_data'" class="empty-state"><p>当前筛选范围没有 {{ labels[props.metric] }} 有效运行峰值</p></div>
  <div v-else class="memory-summary">
    <div class="memory-summary-heading"><div><h3>{{ labels[props.metric] }}</h3><p>按去重后的有效运行峰值计算</p></div><span class="badge">{{ stats.runCount }} 次有效运行</span></div>
    <div class="stats-grid memory-stats-grid">
      <section v-for="card in cards" :key="card.key" class="metric-stat"><span>{{ card.label }}</span><strong>{{ value(stats[card.key]) }}</strong></section>
    </div>
  </div>
</template>
