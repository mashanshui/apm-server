<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import CrashAnalysisPanel from '../components/CrashAnalysisPanel.vue'
import { useAppStore } from '../stores/apps'
import { useSessionStore } from '../stores/session'
import AppLayout from '../components/AppLayout.vue'
import EventIdentityFields from '../components/EventIdentityFields.vue'
import StackTrace from '../components/StackTrace.vue'
import StatusMessage from '../components/StatusMessage.vue'
import { crashApi, errorMessage, isAbortError } from '../api/crashApi'
import type { CrashEventDetailResponse } from '../types/crash'
import { formatDateTime, statusTone } from '../utils/format'
import { filtersToQuery, parseFilters } from '../utils/query'

/** 当前应用角色只适用于相同 appId，防止切换时沿用其他应用权限。 */
const apps = useAppStore()
/** 当前用户用于创建者取消判断。 */
const session = useSessionStore()
const route = useRoute()
const appId = computed(() => String(route.params.appId))
const eventId = computed(() => String(route.params.eventId))
/** 创建与管理权限来自当前应用实时角色，后端独立验证。 */
const analysisRole = computed(() => apps.apps.find(app => app.appId === appId.value)?.role ?? null)
/** Developer 也可创建。 */
const canAnalyze = computed(() => ['OWNER', 'ADMIN', 'DEVELOPER'].includes(analysisRole.value ?? ''))
/** 构建登记与停止核验仅管理员。 */
const canManageAnalysis = computed(() => ['OWNER', 'ADMIN'].includes(analysisRole.value ?? ''))
const event = ref<CrashEventDetailResponse | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
let controller: AbortController | null = null
let requestToken = 0
const showSymbolicated = ref(true)

const hasSymbolicatedText = computed(() => Boolean(event.value?.symbolicatedStackText))

/** 返回符号化失败原因的中文说明。 */
function symbolicationReason(reason: string | null): string {
  const labels: Record<string, string> = {
    mapping_missing: '当前 buildId 没有可用 mapping',
    mapping_unavailable: '符号表存储暂时不可用',
    parser_busy: '符号解析资源繁忙，请稍后刷新',
    retrace_failed: 'R8 Retrace 未能完成本次还原',
    output_limit: '还原结果超过响应大小上限',
  }
  return reason ? (labels[reason] ?? reason) : '当前请求未执行符号化'
}

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

/** 每次进入详情都重新请求当前 mapping 对应的实时还原结果。 */
async function load() {
  const token = ++requestToken
  controller?.abort()
  controller = new AbortController()
  event.value = null
  error.value = null
  loading.value = true
  try {
    // 旧应用/事件的迟到详情不能挂载到当前分析入口。
    const result = await crashApi.event(appId.value, eventId.value, controller.signal)
    if (token === requestToken) event.value = result
  } catch (requestError) {
    if (token === requestToken && !isAbortError(requestError)) {
      error.value = errorMessage(requestError)
    }
  } finally {
    if (token === requestToken) {
      loading.value = false
    }
  }
}

watch(() => route.fullPath, () => { void load() }, { immediate: true })
onBeforeUnmount(() => { requestToken += 1; controller?.abort() })
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
        {{ symbolicationReason(event.symbolicationReason) }}。以下为服务端返回的脱敏原始异常链和堆栈。
      </div>
      <div v-else-if="event.symbolicationStatus === 'failed'" class="notice notice-warning">
        {{ symbolicationReason(event.symbolicationReason) }}，已保留原始异常链。
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
          <div class="meta-item"><dt>指纹</dt><dd class="fingerprint" :title="event.fingerprint">{{ event.fingerprint }}</dd></div>
          <div class="meta-item"><dt>指纹版本</dt><dd>{{ event.fingerprintVersion || '—' }}</dd></div>
          <div class="meta-item"><dt>符号表版本</dt><dd>{{ event.symbolFileRevision ? `revision ${event.symbolFileRevision}` : '—' }}</dd></div>
          <div class="meta-item"><dt>本次原因</dt><dd>{{ symbolicationReason(event.symbolicationReason) }}</dd></div>
        </dl>
        <EventIdentityFields
          :anonymous-device-id="event.anonymousDeviceId"
          :session-id="event.sessionId"
          :process-id="event.processId"
        />
      </section>

      <section v-if="hasSymbolicatedText" class="panel detail-card symbolicated-panel">
        <div class="panel-header" style="padding: 0 0 16px">
          <div><h2>堆栈内容</h2><p>每次打开详情都会使用当前 mapping 实时解析，不保存还原结果。</p></div>
          <div class="segmented-control"><button type="button" :class="{ active: !showSymbolicated }" @click="showSymbolicated = false">原始结构</button><button type="button" :class="{ active: showSymbolicated }" @click="showSymbolicated = true">还原文本</button></div>
        </div>
        <pre v-if="showSymbolicated" class="symbolicated-stack">{{ event.symbolicatedStackText }}</pre>
        <StackTrace v-else :crash="event.rawCrash" />
      </section>
      <StackTrace v-else :crash="event.rawCrash" />
      <CrashAnalysisPanel v-if="event.rawCrash?.kind === 'jvm' && event.rawCrash?.fatal" :app-id="appId" :event-id="eventId" :can-create="canAnalyze" :can-manage="canManageAnalysis" :user-id="session.user?.id ?? null" />
      <div class="detail-actions"><RouterLink class="button" :to="{ name: 'app-symbols', params: { appId }, query: { buildId: event.buildId } }">上传此 buildId 的 mapping</RouterLink></div>
    </template>
  </AppLayout>
</template>
