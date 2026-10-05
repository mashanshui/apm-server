<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppLayout from '../components/AppLayout.vue'
import JankEventTable from '../components/JankEventTable.vue'
import JankFilterBar from '../components/JankFilterBar.vue'
import StatusMessage from '../components/StatusMessage.vue'
import { useJankIssueEvents } from '../composables/useJankIssueEvents'
import type { JankEventSummary, JankFilterForm } from '../types/jank'
import { jankFiltersToQuery, parseJankFilters } from '../utils/jankQuery'

const route = useRoute()
const router = useRouter()
const appId = computed(() => String(route.params.appId))
const fingerprint = computed(() => String(route.params.fingerprint))
const filters = ref<JankFilterForm>({ ...parseJankFilters(route.query), fingerprint: fingerprint.value })
const query = useJankIssueEvents(appId, fingerprint, filters)

watch(() => route.fullPath, () => {
  filters.value = { ...parseJankFilters(route.query), fingerprint: fingerprint.value }
  void query.load()
}, { immediate: true })
onBeforeUnmount(query.cancel)

function applyFilters(next: JankFilterForm): void {
  void router.push({ name: 'jank-issue-events', params: { appId: appId.value, fingerprint: fingerprint.value }, query: jankFiltersToQuery({ ...next, fingerprint: fingerprint.value }) })
}

function openEvent(event: JankEventSummary): void {
  void router.push({ name: 'jank-event-detail', params: { appId: appId.value, eventId: event.eventId }, query: jankFiltersToQuery(filters.value) })
}

const backToIssues = computed(() => ({ name: 'jank-issues', params: { appId: appId.value }, query: jankFiltersToQuery({ ...filters.value, fingerprint: '' }) }))
</script>

<template>
  <AppLayout :app-id="appId">
    <div class="breadcrumb"><RouterLink :to="backToIssues">卡顿问题分析</RouterLink><span class="breadcrumb-separator">/</span><span>Issue 事件</span></div>
    <header class="page-header"><div><p class="eyebrow">JANK ISSUE</p><h1>Issue 事件列表</h1><p class="subtitle">指纹 <span class="fingerprint" :title="fingerprint">{{ fingerprint }}</span></p></div><RouterLink class="button" :to="backToIssues">返回问题列表</RouterLink></header>
    <JankFilterBar :model-value="filters" :loading="query.loading.value" :show-fingerprint="false" @submit="applyFilters" />
    <StatusMessage v-if="query.error.value" kind="error" :message="query.error.value" @retry="query.load" />
    <section v-else class="panel">
      <div class="panel-header"><div><h2>卡顿个例</h2><p>按服务端顺序展示；精确消息耗时与采样估算总耗时分列</p></div><span v-if="query.items.value.length" class="badge">{{ query.items.value.length }} 个事件</span></div>
      <div v-if="query.appendError.value" class="notice">追加失败：{{ query.appendError.value }}，已加载事件不受影响。 <button v-if="query.cursorInvalid.value" class="link-button" type="button" @click="query.load">重新查询</button><button v-else class="link-button" type="button" @click="query.loadMore">重试追加</button></div>
      <JankEventTable :cursor-invalid="query.cursorInvalid.value" :events="query.items.value" :next-cursor="query.nextCursor.value" :loading="query.loading.value" :loading-more="query.loadingMore.value" @open="openEvent" @next="query.loadMore" />
    </section>
  </AppLayout>
</template>
