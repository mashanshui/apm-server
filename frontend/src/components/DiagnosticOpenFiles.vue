<script setup lang="ts">
import type { DiagnosticOpenFiles } from '../types/crashDiagnostics'
import { partitionText } from '../utils/diagnostics'

/** 当前事件已经授权返回的文件描述符分区；null 表示未采集。 */
defineProps<{ openFiles: DiagnosticOpenFiles | null }>()
</script>

<template>
  <section class="panel detail-card diagnostic-card" aria-label="文件描述符">
    <div class="panel-header">
      <div><h2>FD 信息</h2><p>文件描述符总数与有限明细，目标类别已脱敏。</p></div>
      <span class="badge">{{ partitionText(openFiles) }}</span>
    </div>
    <div class="diagnostic-section">
      <template v-if="openFiles">
        <p class="diagnostic-hint">总数 {{ openFiles.count ?? '未知' }} · 已保存 {{ openFiles.entries.length }} 条</p>
        <ul v-if="openFiles.entries.length" class="fd-list"><li v-for="entry in openFiles.entries" :key="entry.fd"><code>{{ entry.fd }}</code><span>{{ entry.target ?? '目标未取得' }}</span></li></ul>
        <p v-else class="diagnostic-empty">{{ openFiles.status === 'ok' ? '采集成功，没有文件描述符明细。' : '没有可展示的文件描述符明细。' }}</p>
      </template>
      <p v-else class="diagnostic-empty">未采集文件描述符。</p>
    </div>
  </section>
</template>
