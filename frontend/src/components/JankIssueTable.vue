<script setup lang="ts">
import type { JankIssueSummary } from '../types/jank'
import { formatDateTime, formatNumber } from '../utils/format'

withDefaults(defineProps<{
  issues: JankIssueSummary[]
  nextCursor: string | null
  loading?: boolean
  loadingMore?: boolean
}>(), { loading: false, loadingMore: false })

const emit = defineEmits<{ open: [issue: JankIssueSummary]; next: [] }>()
const duration = (value: number | null) => value === null ? '—' : `${formatNumber(value)} ms`
</script>

<template>
  <div v-if="loading" class="loading-state"><p>正在加载卡顿问题…</p></div>
  <div v-else-if="issues.length === 0" class="empty-state"><p>当前筛选范围没有卡顿问题</p></div>
  <template v-else>
    <div class="table-wrap jank-wide-table">
      <table class="data-table">
        <thead><tr><th>问题指纹</th><th>场景 / 算法</th><th>事件</th><th>会话</th><th>设备</th><th>精确消息 P50 / P90 / P99</th><th>采样估算 P50 / P90 / P99</th><th>首次 / 最近出现</th></tr></thead>
        <tbody>
          <tr v-for="issue in issues" :key="issue.fingerprint">
            <td><button class="link-button fingerprint" type="button" :title="issue.fingerprint" @click="emit('open', issue)">{{ issue.fingerprint }}</button></td>
            <td>{{ issue.scene || '—' }} / {{ issue.algorithmVersion || '—' }}</td>
            <td>{{ formatNumber(issue.eventCount) }}</td><td>{{ formatNumber(issue.affectedSessionCount) }}</td><td>{{ formatNumber(issue.affectedDeviceCount) }}</td>
            <td>{{ duration(issue.exactMessageDuration.p50Ms) }} / {{ duration(issue.exactMessageDuration.p90Ms) }} / {{ duration(issue.exactMessageDuration.p99Ms) }}</td>
            <td>{{ duration(issue.estimatedStackDuration.p50Ms) }} / {{ duration(issue.estimatedStackDuration.p90Ms) }} / {{ duration(issue.estimatedStackDuration.p99Ms) }}</td>
            <td>{{ formatDateTime(issue.firstSeenAt) }} / {{ formatDateTime(issue.lastSeenAt) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="pagination-bar"><span>{{ nextCursor ? '可继续加载更多问题' : '已显示全部问题' }}</span><button class="button" type="button" :disabled="!nextCursor || loadingMore" @click="emit('next')">{{ loadingMore ? '加载中…' : '加载更多' }}</button></div>
  </template>
</template>
