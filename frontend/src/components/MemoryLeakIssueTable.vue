<script setup lang="ts">
import { ref } from 'vue'
import type { MemoryLeakIssue } from '../types/memoryLeak'
import { formatDateTime, formatNumber, formatPercent } from '../utils/format'

const props = withDefaults(defineProps<{
  issues: MemoryLeakIssue[]
  total: number
  page: number
  pageSize: number
  loading?: boolean
}>(), { loading: false })

const emit = defineEmits<{ previous: []; next: []; sort: [field: 'occurrences' | 'affectedDevices' | 'lastOccurredAt'] }>()
// 使用 ref 包装集合，确保展开/收起引用链后 Vue 能够重新渲染明细行。
const expanded = ref(new Set<string>())

function toggle(signature: string): void {
  const next = new Set(expanded.value)
  if (next.has(signature)) next.delete(signature)
  else next.add(signature)
  expanded.value = next
}

function isExpanded(signature: string): boolean { return expanded.value.has(signature) }
function pageCount(): number { return Math.max(1, Math.ceil(props.total / props.pageSize)) }
</script>

<template>
  <div v-if="loading" class="loading-state"><p>正在加载泄漏问题…</p></div>
  <div v-else-if="issues.length === 0" class="empty-state"><p>当前筛选范围没有 SDK 报告的疑似问题</p></div>
  <template v-else>
    <div class="table-wrap memory-leak-table-wrap">
      <table class="data-table memory-leak-table">
        <thead><tr>
          <th>问题</th><th>引用链摘要</th>
          <th><button class="table-sort-button" type="button" @click="emit('sort', 'lastOccurredAt')">最近发生</button></th>
          <th><button class="table-sort-button" type="button" @click="emit('sort', 'occurrences')">发生次数</button></th>
          <th><button class="table-sort-button" type="button" @click="emit('sort', 'affectedDevices')">影响设备</button></th><th>受影响版本</th>
        </tr></thead>
        <tbody>
          <template v-for="issue in issues" :key="issue.signature">
            <tr>
              <td class="memory-leak-issue-cell"><strong :title="issue.leakClass || '未知类名'">{{ issue.leakClass || '未知类名' }}</strong><span>{{ issue.leakReason || '未知触发原因' }}</span><code :title="issue.signature">{{ issue.signature }}</code></td>
              <td class="memory-leak-path-cell"><span>{{ issue.gcRoot || '未知 GC Root' }}</span><span v-if="issue.path.length">{{ issue.path[0].reference || '未知引用' }}<template v-if="issue.path.length > 1"> … {{ issue.path[issue.path.length - 1].reference || '未知引用' }}</template></span><span v-else>无引用链</span><button v-if="issue.path.length" class="link-button" type="button" @click="toggle(issue.signature)">{{ isExpanded(issue.signature) ? '收起引用链' : `展开 ${issue.path.length} 个节点` }}</button></td>
              <td>{{ formatDateTime(issue.lastOccurredAt) }}</td>
              <td><strong>{{ formatNumber(issue.occurrences) }}</strong><small>{{ formatPercent(issue.occurrenceRatio) }}</small></td>
              <td><strong>{{ formatNumber(issue.affectedDevices) }}</strong><small>{{ formatPercent(issue.deviceRatio) }}</small></td>
              <td><span class="version-list">{{ issue.versions.length ? issue.versions.join('、') : '—' }}</span></td>
            </tr>
            <tr v-if="isExpanded(issue.signature)" class="memory-leak-expanded-row"><td colspan="6"><div class="memory-leak-path-detail"><strong>完整引用链</strong><ol><li v-for="(node, index) in issue.path" :key="`${issue.signature}-${index}`"><span class="path-index">{{ index + 1 }}</span><code>{{ node.reference || '未知引用' }}</code><span class="path-type">{{ node.referenceType || '未知类型' }}</span><span v-if="node.declaredClass" class="path-class">{{ node.declaredClass }}</span></li></ol></div></td></tr>
          </template>
        </tbody>
      </table>
    </div>
    <div class="pagination-bar"><span>第 {{ props.page }} / {{ pageCount() }} 页，共 {{ props.total }} 个问题</span><div class="pagination-actions"><button class="button" type="button" :disabled="props.page <= 1" @click="emit('previous')">上一页</button><button class="button" type="button" :disabled="props.page >= pageCount()" @click="emit('next')">下一页</button></div></div>
  </template>
</template>
