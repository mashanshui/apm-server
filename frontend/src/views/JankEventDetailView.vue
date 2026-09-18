<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import AppLayout from '../components/AppLayout.vue'
import EventIdentityFields from '../components/EventIdentityFields.vue'
import JankCallTree from '../components/JankCallTree.vue'
import JankFlameGraph from '../components/JankFlameGraph.vue'
import JankTimeline from '../components/JankTimeline.vue'
import StatCard from '../components/StatCard.vue'
import StatusMessage from '../components/StatusMessage.vue'
import { jankApi } from '../api/jankApi'
import { createJankQueryRegion } from '../composables/useJankQuery'
import type { JankEventDetailResponse } from '../types/jank'
import { formatDateTime, formatNumber } from '../utils/format'
import { formatNanoseconds } from '../utils/jankEvidence'
import { jankFiltersToQuery, parseJankFilters } from '../utils/jankQuery'

const route = useRoute()
const appId = computed(() => String(route.params.appId))
const eventId = computed(() => String(route.params.eventId))
const contextFilters = computed(() => parseJankFilters(route.query))
const detail = createJankQueryRegion<JankEventDetailResponse>()
const activeEvidence = ref<'timeline' | 'tree' | 'flame'>('timeline')
const event = detail.data

async function load(): Promise<void> {
  detail.reset()
  const currentAppId = appId.value
  const currentEventId = eventId.value
  await detail.run((signal) => jankApi.event(currentAppId, currentEventId, signal))
}

watch(() => route.fullPath, () => { void load() }, { immediate: true })
onBeforeUnmount(detail.cancel)

const backToIssues = computed(() => ({
  name: 'jank-issues',
  params: { appId: appId.value },
  query: jankFiltersToQuery({ ...contextFilters.value, fingerprint: '' }),
}))
const backToIssue = computed(() => contextFilters.value.fingerprint ? ({
  name: 'jank-issue-events',
  params: { appId: appId.value, fingerprint: contextFilters.value.fingerprint },
  query: jankFiltersToQuery(contextFilters.value),
}) : null)
</script>

<template>
  <AppLayout :app-id="appId">
    <div class="breadcrumb"><RouterLink :to="backToIssues">卡顿问题分析</RouterLink><template v-if="backToIssue"><span class="breadcrumb-separator">/</span><RouterLink :to="backToIssue">Issue 事件</RouterLink></template><span class="breadcrumb-separator">/</span><span>事件详情</span></div>
    <header class="page-header"><div><p class="eyebrow">JANK EVENT</p><h1>卡顿事件详情</h1><p class="subtitle">事件 ID <span class="fingerprint" :title="eventId">{{ eventId }}</span></p></div><RouterLink class="button" :to="backToIssue || backToIssues">返回列表</RouterLink></header>

    <StatusMessage v-if="detail.error.value" kind="error" :message="detail.error.value" @retry="load" />
    <StatusMessage v-else-if="detail.loading.value" kind="loading" message="正在加载卡顿事件和采样证据…" />

    <template v-else-if="event">
      <div class="notice notice-info">精确消息总耗时来自消息开始/结束证据；时间片、调用树和火焰图中的方法耗时均为采样估算。</div>
      <section class="panel detail-card jank-region">
        <div class="panel-header compact-panel-header"><div><h2>事件元数据</h2><p>App {{ event.appId }} · 包名 {{ event.packageName }}</p></div><span class="badge badge-success">{{ event.algorithmVersion }}</span></div>
        <dl class="meta-grid">
          <div class="meta-item"><dt>发生 / 接收时间</dt><dd>{{ formatDateTime(event.occurredAt) }} / {{ formatDateTime(event.receivedAt) }}</dd></div>
          <div class="meta-item"><dt>场景</dt><dd>{{ event.scene || '—' }}</dd></div>
          <div class="meta-item"><dt>应用版本 / 构建</dt><dd>{{ event.appVersion || '—' }}（{{ event.versionCode ?? '—' }}）/ {{ event.buildId || '—' }}</dd></div>
          <div class="meta-item"><dt>渠道 / 环境</dt><dd>{{ event.channel || '—' }} / {{ event.environment || '—' }}</dd></div>
          <div class="meta-item"><dt>Android / 设备</dt><dd>{{ event.osVersion || '—' }} / {{ event.deviceModel || '—' }}</dd></div>
          <div class="meta-item"><dt>网络类型</dt><dd>{{ event.networkType || '—' }}</dd></div>
          <div class="meta-item"><dt>问题指纹</dt><dd class="fingerprint" :title="event.fingerprint">{{ event.fingerprint }}</dd></div>
          <div class="meta-item"><dt>指纹版本</dt><dd>{{ event.fingerprintVersion || '—' }}</dd></div>
        </dl>
        <EventIdentityFields
          :anonymous-device-id="event.anonymousDeviceId"
          :session-id="event.sessionId"
          :process-id="event.processId"
        />
      </section>

      <div class="stats-grid jank-stats-grid evidence-stats">
        <StatCard label="精确消息总耗时" :value="formatNanoseconds(event.analysis.exactMessageDurationNs)" footnote="精确开始/结束证据" tone="danger" />
        <StatCard label="卡顿阈值" :value="formatNanoseconds(event.jank.thresholdNs)" footnote="客户端判定阈值" />
        <StatCard label="采样间隔" :value="formatNanoseconds(event.jank.samplingIntervalNs)" footnote="目标采样频率" />
        <StatCard label="采样估算覆盖" :value="formatNanoseconds(event.analysis.coveredDurationNs)" footnote="仅成功采样区间" tone="success" />
        <StatCard label="未覆盖空洞" :value="formatNanoseconds(event.analysis.uncoveredDurationNs)" footnote="不归属任何方法" tone="warning" />
        <StatCard label="未归属采样估算" :value="formatNanoseconds(event.analysis.estimatedUnattributedDurationNs)" footnote="树节点内无法继续归属" />
      </div>

      <section class="panel detail-card jank-region">
        <div class="panel-header compact-panel-header"><div><h2>采集质量</h2><p>期望、已解析和缺失计数均由服务端解析主线程证据派生</p></div></div>
        <dl class="quality-grid">
          <div><dt>期望采样</dt><dd>{{ formatNumber(event.analysis.expectedSampleCount) }}</dd></div>
          <div><dt>已解析采样</dt><dd>{{ formatNumber(event.analysis.parsedSampleCount) }}</dd></div>
          <div><dt>缺失采样</dt><dd>{{ formatNumber(event.analysis.missingSampleCount) }}</dd></div>
        </dl>
      </section>

      <section class="panel evidence-panel">
        <div class="panel-header"><div><h2>采样估算证据</h2><p>空洞保持为空；视图不会用精确消息总耗时补齐缺失区间</p></div><div class="segmented-control" role="tablist" aria-label="卡顿证据视图"><button v-for="tab in ([['timeline', '时间片'], ['tree', '估算调用树'], ['flame', '估算火焰图']] as const)" :key="tab[0]" type="button" role="tab" :aria-selected="activeEvidence === tab[0]" :class="{ active: activeEvidence === tab[0] }" @click="activeEvidence = tab[0]">{{ tab[1] }}</button></div></div>
        <div class="evidence-body">
          <JankTimeline v-if="activeEvidence === 'timeline'" :slices="event.analysis.sampleSlices" :stack-dictionary="event.analysis.stackDictionary" :exact-message-duration-ns="event.analysis.exactMessageDurationNs" />
          <JankCallTree v-else-if="activeEvidence === 'tree'" :nodes="event.analysis.callTree" />
          <JankFlameGraph v-else :nodes="event.analysis.callTree" />
        </div>
      </section>
    </template>
  </AppLayout>
</template>
