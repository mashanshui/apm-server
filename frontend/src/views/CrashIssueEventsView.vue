<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppLayout from '../components/AppLayout.vue'
import EventTable from '../components/EventTable.vue'
import FilterBar from '../components/FilterBar.vue'
import StatusMessage from '../components/StatusMessage.vue'
import { useCrashIssueEvents } from '../composables/useCrashIssueEvents'
import type { CrashFilterForm, CrashEventSummary } from '../types/crash'
import { filtersToQuery, parseFilters } from '../utils/query'

const route = useRoute()
const router = useRouter()
const appId = computed(() => String(route.params.appId))
const fingerprint = computed(() => String(route.params.fingerprint))
const filters = ref<CrashFilterForm>(parseFilters(route.query))
const {
  events,
  nextCursor,
  loading,
  error,
  load,
  loadMore,
} = useCrashIssueEvents(appId, fingerprint, filters)

watch(() => route.fullPath, () => {
  filters.value = { ...parseFilters(route.query), fingerprint: fingerprint.value }
  void load()
}, { immediate: true })

function applyFilters(nextFilters: CrashFilterForm) {
  void router.push({
    name: 'crash-issue-events',
    params: { appId: appId.value, fingerprint: fingerprint.value },
    query: filtersToQuery({ ...nextFilters, fingerprint: fingerprint.value }),
  })
}

function openEvent(event: CrashEventSummary) {
  void router.push({
    name: 'crash-event-detail',
    params: { appId: appId.value, eventId: event.eventId },
    query: filtersToQuery(filters.value),
  })
}

function backToOverview() {
  return {
    name: 'crash-overview',
    params: { appId: appId.value },
    query: filtersToQuery(filters.value),
  }
}
</script>

<template>
  <AppLayout :app-id="appId">
    <div class="breadcrumb">
      <RouterLink :to="backToOverview()">JVM Crash</RouterLink>
      <span class="breadcrumb-separator">/</span>
      <span>问题事件</span>
    </div>

    <header class="page-header">
      <div>
        <p class="eyebrow">CRASH ISSUE</p>
        <h1>问题事件</h1>
        <p class="subtitle">指纹 <span class="fingerprint">{{ fingerprint }}</span></p>
      </div>
      <RouterLink class="button" :to="backToOverview()">返回总览</RouterLink>
    </header>

    <FilterBar :model-value="filters" :loading="loading" @submit="applyFilters" />

    <StatusMessage
      v-if="error"
      kind="error"
      :message="error"
      @retry="load"
    />

    <section class="panel">
      <div class="panel-header">
        <div>
          <h2>事件列表</h2>
          <p>按发生时间倒序展示，进入事件可查看原始堆栈</p>
        </div>
        <span v-if="events.length" class="badge">{{ events.length }} 个事件</span>
      </div>
      <EventTable
        :events="events"
        :next-cursor="nextCursor"
        :loading="loading"
        @open="openEvent"
        @next="loadMore"
      />
    </section>
  </AppLayout>
</template>
