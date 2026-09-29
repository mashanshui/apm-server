<script setup lang="ts">
import type { CrashIssueSummary } from '../types/crash'
import { formatDateTime, formatNumber } from '../utils/format'

defineProps<{
  issues: CrashIssueSummary[]
  nextCursor: string | null
  loading?: boolean
  cursorInvalid?: boolean
}>()

const emit = defineEmits<{
  open: [issue: CrashIssueSummary]
  next: []
}>()
</script>

<template>
  <div v-if="loading" class="loading-state"><p>正在加载问题排行…</p></div>
  <div v-else-if="issues.length === 0" class="empty-state"><p>当前范围没有 Crash 问题</p></div>
  <template v-else>
    <div class="table-wrap">
      <table class="data-table">
        <thead>
          <tr>
            <th>问题</th>
            <th>异常类型</th>
            <th>事件</th>
            <th>崩溃会话</th>
            <th>设备</th>
            <th>最近出现</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="issue in issues" :key="issue.fingerprint">
            <td>
              <button class="link-button fingerprint" type="button" :title="issue.fingerprint" @click="emit('open', issue)">
                {{ issue.fingerprint }}
              </button>
            </td>
            <td>{{ issue.exceptionType || '未知异常' }}</td>
            <td>{{ formatNumber(issue.eventCount) }}</td>
            <td>{{ formatNumber(issue.crashedSessionCount) }}</td>
            <td>{{ formatNumber(issue.affectedDeviceCount) }}</td>
            <td>{{ formatDateTime(issue.lastSeenAt) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="pagination-bar">
      <span>{{ cursorInvalid ? '游标已失效，请重新查询' : nextCursor ? '可继续加载更多问题' : '已显示全部问题' }}</span>
      <div class="pagination-actions">
        <button class="button" type="button" :disabled="!nextCursor || loading || cursorInvalid" @click="emit('next')">
          加载更多
        </button>
      </div>
    </div>
  </template>
</template>
