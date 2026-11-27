<script setup lang="ts">
import type { DiagnosticMemory } from '../types/crashDiagnostics'
import { bytes, partitionText } from '../utils/diagnostics'

/** 当前事件已经授权返回的内存分区；null 表示未采集。 */
defineProps<{ memory: DiagnosticMemory | null }>()
/** 内存指标名称明确区分 Runtime 堆和 Debug PSS。 */
const memoryMetrics: Array<{ key: keyof DiagnosticMemory; label: string }> = [
  { key: 'javaHeapUsedBytes', label: 'Runtime Java 堆已用' },
  { key: 'javaHeapMaxBytes', label: 'Runtime Java 堆上限' },
  { key: 'javaHeapPssBytes', label: 'Java 堆 PSS' },
  { key: 'nativeHeapPssBytes', label: 'Native 堆 PSS' },
  { key: 'nativeHeapAllocatedBytes', label: 'Native 堆已分配' },
  { key: 'totalPssBytes', label: '进程总 PSS' },
  { key: 'rssBytes', label: '进程 RSS' },
  { key: 'vssBytes', label: '进程 VSS' },
  { key: 'swapBytes', label: '进程 Swap' },
]
/** 可展开的有界 proc 文本。 */
const memoryTexts = [{ key: 'meminfo', label: 'meminfo' }, { key: 'statusText', label: '进程 status' }, { key: 'limits', label: '进程 limits' }] as const
</script>

<template>
  <section class="panel detail-card diagnostic-card" aria-label="内存快照">
    <div class="panel-header">
      <div><h2>内存快照</h2><p>Runtime 堆与 Debug PSS 口径独立展示，空值表示未知。</p></div>
      <span class="badge">{{ partitionText(memory) }}</span>
    </div>
    <div class="diagnostic-section">
      <template v-if="memory">
        <dl class="diagnostic-grid memory-grid"><div v-for="metric in memoryMetrics" :key="metric.key"><dt>{{ metric.label }}</dt><dd>{{ bytes(memory[metric.key]) }}</dd></div></dl>
        <div class="diagnostic-details"><details v-for="item in memoryTexts" :key="item.key"><summary>{{ item.label }}</summary><pre v-if="memory[item.key]" class="diagnostic-pre">{{ memory[item.key] }}</pre><p v-else class="diagnostic-empty">{{ memory[item.key] === '' ? '内容为空。' : '未取得原文。' }}</p></details></div>
      </template>
      <p v-else class="diagnostic-empty">未采集内存信息。</p>
    </div>
  </section>
</template>
