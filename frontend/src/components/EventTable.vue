<script setup lang="ts">
import type { CrashEventSummary } from '../types/crash'
import { formatDateTime } from '../utils/format'

defineProps<{
  events: CrashEventSummary[]
  nextCursor: string | null
  loading?: boolean
}>()

const emit = defineEmits<{
  open: [event: CrashEventSummary]
  next: []
}>()
</script>

<template>
  <div v-if="loading" class="loading-state"><p>正在加载 Crash 事件…</p></div>
  <div v-else-if="events.length === 0" class="empty-state"><p>当前问题没有事件</p></div>
  <template v-else>
    <div class="table-wrap">
      <table class="data-table">
        <thead>
          <tr>
            <th>发生时间</th>
            <th>事件 ID</th>
            <th>版本 / 构建</th>
            <th>设备 / 系统</th>
            <th>异常类型</th>
            <th>状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="event in events" :key="event.eventId">
            <td>{{ formatDateTime(event.occurredAt) }}</td>
            <td>
              <button class="link-button fingerprint" type="button" :title="event.eventId" @click="emit('open', event)">
                {{ event.eventId }}
              </button>
            </td>
            <td>{{ event.appVersion }} / {{ event.buildId }}</td>
            <td>{{ event.deviceModel }} / Android {{ event.osVersion }}</td>
            <td>{{ event.exceptionType || '未知异常' }}</td>
            <td>
              <span class="badge" :class="event.symbolicationStatus === 'raw_only' ? 'badge-warning' : 'badge-success'">
                {{ event.symbolicationStatus || '未知' }}
              </span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="pagination-bar">
      <span>{{ nextCursor ? '可继续加载更多事件' : '已显示全部事件' }}</span>
      <div class="pagination-actions">
        <button class="button" type="button" :disabled="!nextCursor || loading" @click="emit('next')">
          加载更多
        </button>
      </div>
    </div>
  </template>
</template>
