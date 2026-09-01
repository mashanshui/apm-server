<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import AppLayout from '../components/AppLayout.vue'
import StackTrace from '../components/StackTrace.vue'
import StatusMessage from '../components/StatusMessage.vue'
import { crashApi, errorMessage, isAbortError } from '../api/crashApi'
import type { CrashEventDetailResponse } from '../types/crash'
import { formatDateTime, statusTone } from '../utils/format'
import { filtersToQuery, parseFilters } from '../utils/query'

const route = useRoute()
const appId = computed(() => String(route.params.appId))
const eventId = computed(() => String(route.params.eventId))
const event = ref<CrashEventDetailResponse | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
let controller: AbortController | null = null
let requestToken = 0

const contextFilters = computed(() => parseFilters(route.query))
const backToIssue = computed(() => {
  if (!contextFilters.value.fingerprint) {
    return null
  }
  return {
    name: 'crash-issue-events',
    params: {
      appId: appId.value,
      fingerprint: contextFilters.value.fingerprint,
    },
    query: filtersToQuery(contextFilters.value),
  }
})
const backToOverview = computed(() => ({
  name: 'crash-overview',
  params: { appId: appId.value },
  query: filtersToQuery(contextFilters.value),
}))

async function load() {
  const token = ++requestToken
  controller?.abort()
  controller = new AbortController()
  event.value = null
  error.value = null
  loading.value = true
  try {
    event.value = await crashApi.event(appId.value, eventId.value, controller.signal)
  } catch (requestError) {
    if (!isAbortError(requestError)) {
      error.value = errorMessage(requestError)
    }
  } finally {
    if (token === requestToken) {
      loading.value = false
    }
  }
}

watch(() => route.fullPath, () => { void load() }, { immediate: true })
</script>

<template>
  <AppLayout :app-id="appId">
    <div class="breadcrumb">
      <RouterLink :to="backToOverview">JVM Crash</RouterLink>
      <template v-if="backToIssue">
        <span class="breadcrumb-separator">/</span>
        <RouterLink :to="backToIssue">问题事件</RouterLink>
      </template>
      <span class="breadcrumb-separator">/</span>
      <span>事件详情</span>
    </div>

    <header class="page-header">
      <div>
        <p class="eyebrow">CRASH EVENT</p>
        <h1>事件详情</h1>
        <p class="subtitle">事件 ID <span class="fingerprint">{{ eventId }}</span></p>
      </div>
      <RouterLink class="button" :to="backToIssue || backToOverview">返回列表</RouterLink>
    </header>

    <StatusMessage
      v-if="error"
      kind="error"
      :message="error"
      @retry="load"
    />
    <StatusMessage v-else-if="loading" kind="loading" message="正在加载事件详情…" />

    <template v-else-if="event">
      <div v-if="event.symbolicationStatus === 'raw_only'" class="notice">
        当前事件尚未完成符号化，以下为服务端返回的脱敏原始异常链和堆栈。
      </div>

      <section class="panel detail-card" style="margin-bottom: 20px">
        <div class="panel-header" style="padding: 0 0 17px">
          <div>
            <h2>事件元数据</h2>
            <p>App {{ event.appId }} · 包名 {{ event.packageName }}</p>
          </div>
          <span class="badge" :class="`badge-${statusTone(event.symbolicationStatus === 'raw_only' ? 'denominator_insufficient' : 'ok')}`">
            {{ event.symbolicationStatus || '未知状态' }}
          </span>
        </div>
        <dl class="meta-grid">
          <div class="meta-item"><dt>发生时间</dt><dd>{{ formatDateTime(event.occurredAt) }}</dd></div>
          <div class="meta-item"><dt>接收时间</dt><dd>{{ formatDateTime(event.receivedAt) }}</dd></div>
          <div class="meta-item"><dt>异常类型</dt><dd>{{ event.exceptionType || '未知异常' }}</dd></div>
          <div class="meta-item"><dt>应用版本</dt><dd>{{ event.appVersion }}（{{ event.versionCode ?? '—' }}）</dd></div>
          <div class="meta-item"><dt>构建</dt><dd>{{ event.buildId || '—' }}</dd></div>
          <div class="meta-item"><dt>渠道 / 环境</dt><dd>{{ event.channel || '—' }} / {{ event.environment || '—' }}</dd></div>
          <div class="meta-item"><dt>Android / 设备</dt><dd>{{ event.osVersion || '—' }} / {{ event.deviceModel || '—' }}</dd></div>
          <div class="meta-item"><dt>网络类型</dt><dd>{{ event.networkType || '—' }}</dd></div>
          <div class="meta-item"><dt>会话 ID</dt><dd>{{ event.sessionId || '—' }}</dd></div>
          <div class="meta-item"><dt>匿名设备 ID</dt><dd>{{ event.anonymousDeviceId || '—' }}</dd></div>
          <div class="meta-item"><dt>指纹</dt><dd class="fingerprint" :title="event.fingerprint">{{ event.fingerprint }}</dd></div>
          <div class="meta-item"><dt>指纹版本</dt><dd>{{ event.fingerprintVersion || '—' }}</dd></div>
        </dl>
      </section>

      <StackTrace :crash="event.rawCrash" />
    </template>
  </AppLayout>
</template>
