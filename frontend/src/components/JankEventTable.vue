<script setup lang="ts">
import type { JankEventSummary } from '../types/jank'
import { formatDateTime, formatNumber } from '../utils/format'

withDefaults(defineProps<{ events: JankEventSummary[]; nextCursor: string | null; loading?: boolean; loadingMore?: boolean; cursorInvalid?: boolean }>(), { loading: false, loadingMore: false, cursorInvalid: false })
const emit = defineEmits<{ open: [event: JankEventSummary]; next: [] }>()
const duration = (value: number | null) => value === null ? '—' : `${formatNumber(value)} ms`
</script>

<template>
  <div v-if="loading" class="loading-state"><p>正在加载卡顿事件…</p></div>
  <div v-else-if="events.length === 0" class="empty-state"><p>当前问题没有事件</p></div>
  <template v-else>
    <div class="table-wrap jank-wide-table"><table class="data-table">
      <thead><tr><th>发生时间</th><th>事件 ID</th><th>场景 / 算法</th><th>精确消息耗时</th><th>采样估算总耗时</th><th>覆盖 / 空洞</th><th>版本</th><th>设备 / 系统</th></tr></thead>
      <tbody><tr v-for="event in events" :key="event.eventId">
        <td>{{ formatDateTime(event.occurredAt) }}</td>
        <td><button class="link-button fingerprint" type="button" :title="event.eventId" @click="emit('open', event)">{{ event.eventId }}</button></td>
        <td>{{ event.scene || '—' }} / {{ event.algorithmVersion || '—' }}</td>
        <td>{{ duration(event.exactMessageDurationMs) }}</td><td>{{ duration(event.estimatedDurationMs) }}</td><td>{{ duration(event.coveredDurationMs) }} / {{ duration(event.uncoveredDurationMs) }}</td>
        <td>{{ event.appVersion || '—' }} / {{ event.buildId || '—' }}</td><td>{{ event.deviceModel || '—' }} / Android {{ event.osVersion || '—' }}</td>
      </tr></tbody>
    </table></div>
    <div class="pagination-bar"><span>{{ cursorInvalid ? '游标已失效，请重新查询' : nextCursor ? '可继续加载更多事件' : '已显示全部事件' }}</span><button class="button" type="button" :disabled="cursorInvalid || !nextCursor || loadingMore" @click="emit('next')">{{ loadingMore ? '加载中…' : '加载更多' }}</button></div>
  </template>
</template>
