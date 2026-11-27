<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { DiagnosticLogBuffer, DiagnosticLogcat } from '../types/crashDiagnostics'
import { partitionText, timestamp } from '../utils/diagnostics'

/** 当前事件已经授权返回的日志分区；null 表示未采集。 */
const props = defineProps<{ logcat: DiagnosticLogcat | null }>()
/** 当前查看的日志缓冲区。 */
const selectedBuffer = ref<'main' | 'system' | 'events'>('main')
/** 固定缓冲区名称和展示标签。 */
const buffers = [{ key: 'main', label: 'Main' }, { key: 'system', label: 'System' }, { key: 'events', label: 'Events' }] as const
/** 当前缓冲区可能未采集。 */
const logBuffer = computed<DiagnosticLogBuffer | null>(() => props.logcat?.[selectedBuffer.value] ?? null)

// 事件变化后清除旧的日志缓冲区选择。
watch(() => props.logcat, () => { selectedBuffer.value = 'main' })
</script>

<template>
  <section class="panel detail-card diagnostic-card" aria-label="日志">
    <div class="panel-header">
      <div><h2>日志</h2><p>Logcat 各缓冲区独立保留采集状态，受设备与权限影响。</p></div>
      <span class="badge">{{ partitionText(logcat) }}</span>
    </div>
    <div class="diagnostic-section">
      <template v-if="logcat">
        <div class="segmented-control" role="tablist" aria-label="日志缓冲区">
          <button v-for="buffer in buffers" :key="buffer.key" type="button" role="tab" :aria-selected="selectedBuffer === buffer.key"
            :class="{ active: selectedBuffer === buffer.key }" @click="selectedBuffer = buffer.key">{{ buffer.label }}</button>
        </div>
        <p class="diagnostic-hint">{{ partitionText(logBuffer) }}<template v-if="logBuffer?.capturedAt != null"> · 采集于 {{ timestamp(logBuffer.capturedAt) }}</template></p>
        <pre v-if="logBuffer?.lines.length" class="diagnostic-pre">{{ logBuffer.lines.join('\n') }}</pre>
        <p v-else class="diagnostic-empty">{{ logBuffer?.status === 'ok' ? '采集成功，日志为空。' : '没有可展示的日志。' }}</p>
      </template>
      <p v-else class="diagnostic-empty">未采集日志。</p>
    </div>
  </section>
</template>
