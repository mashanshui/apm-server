<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import CrashAnalysisPanel from '../components/CrashAnalysisPanel.vue'
import DetailTabs from '../components/DetailTabs.vue'
import DiagnosticLogcat from '../components/DiagnosticLogcat.vue'
import DiagnosticMemory from '../components/DiagnosticMemory.vue'
import DiagnosticOpenFiles from '../components/DiagnosticOpenFiles.vue'
import DiagnosticProcess from '../components/DiagnosticProcess.vue'
import DiagnosticThreads from '../components/DiagnosticThreads.vue'
import { useAppStore } from '../stores/apps'
import { useSessionStore } from '../stores/session'
import AppLayout from '../components/AppLayout.vue'
import EventIdentityFields from '../components/EventIdentityFields.vue'
import StackTrace from '../components/StackTrace.vue'
import NativeCrashPanel from '../components/NativeCrashPanel.vue'
import StatusMessage from '../components/StatusMessage.vue'
import { crashApi, errorMessage, isAbortError } from '../api/crashApi'
import type { CrashEventDetailResponse } from '../types/crash'
import { formatDateTime } from '../utils/format'
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
/** 认证失效后阻止当前页面重新加载，直到建立新的会话。 */
const authInvalidated = ref(false)
let controller: AbortController | null = null
let requestToken = 0

/** 返回符号化失败原因的中文说明。 */
function symbolicationReason(reason: string | null): string {
  const labels: Record<string, string> = {
    awaiting_processing: '等待后台处理；请确认此 buildId 的 mapping 已上传',
    mapping_missing: '当前 buildId 没有可用 mapping',
    mapping_unavailable: '符号表存储暂时不可用',
    parser_busy: '符号解析资源繁忙，请稍后刷新',
    retrace_failed: 'R8 Retrace 未能完成本次还原',
    output_limit: '还原结果超过响应大小上限',
    native_symbolication_deferred: '等待后台 Native 符号还原',
  }
  return reason ? (labels[reason] ?? reason) : '无失败原因'
}

const contextFilters = computed(() => parseFilters(route.query))
/** 当前选中的详情分区；事件切换后回到堆栈。 */
const activeTab = ref('stack')
/** 详情分区按钮列表；本地分析仅 JVM fatal 事件提供。 */
const detailTabs = computed(() => {
  const tabs = [
    { key: 'stack', label: '崩溃堆栈' },
    { key: 'threads', label: '线程快照' },
    { key: 'logcat', label: '日志' },
    { key: 'memory', label: '内存快照' },
    { key: 'openFiles', label: 'FD 信息' },
    { key: 'process', label: '进程信息' },
  ]
  if (event.value?.crashKind === 'jvm' && event.value.fatal) tabs.push({ key: 'analysis', label: '本地分析' })
  return tabs
})
// 更换事件后不沿用上一个事件的分区选择。
watch(() => eventId.value, () => { activeTab.value = 'stack' })
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

/** 详情仅读取持久化状态，不触发还原。 */
async function load() {
  // 跳转登录页可能被延迟，期间的路由变化和重试不能重新加载旧详情。
  if (authInvalidated.value) return
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

/** 认证失效时立即取消在途请求并清除当前详情，不依赖页面跳转完成。 */
function invalidateDetailForAuth() {
  if (authInvalidated.value) return
  authInvalidated.value = true
  requestToken += 1
  controller?.abort()
  controller = null
  event.value = null
  error.value = null
  loading.value = false
  console.info('[CrashEventDetailView] 会话失效，已清除详情并取消请求')
}

// HTTP 401 事件和主动退出登录都可能先于路由卸载，分别接入同一失效处理。
window.addEventListener('apm:auth-expired', invalidateDetailForAuth)
watch(() => session.isAuthenticated, (authenticated, previouslyAuthenticated) => {
  if (!authenticated && previouslyAuthenticated) {
    invalidateDetailForAuth()
  } else if (authenticated && !previouslyAuthenticated && authInvalidated.value) {
    // 只有建立新会话后才允许重新请求，旧请求仍受原令牌隔离。
    authInvalidated.value = false
    console.info('[CrashEventDetailView] 新会话已建立，重新加载详情')
    void load()
  }
}, { flush: 'sync' })
watch(() => route.fullPath, () => { void load() }, { immediate: true })
onBeforeUnmount(() => {
  window.removeEventListener('apm:auth-expired', invalidateDetailForAuth)
  requestToken += 1
  controller?.abort()
})
</script>

<template>
  <AppLayout :app-id="appId">
    <div class="breadcrumb">
      <RouterLink :to="backToOverview">Crash</RouterLink>
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

    <div v-if="authInvalidated" class="notice notice-warning" role="status">登录状态已失效，请重新登录。</div>
    <StatusMessage
      v-else-if="error"
      kind="error"
      :message="error"
      @retry="load"
    />
    <StatusMessage v-else-if="loading" kind="loading" message="正在加载事件详情…" />

    <template v-else-if="event">
      <div v-if="event.crashKind === 'native'" class="notice">Native 帧按实际符号来源独立处理；归组仍按构建隔离。</div>
      <div v-if="event.nativeFrameCounts" class="notice">主栈：已还原 {{ event.nativeFrameCounts.symbolicated }} · 等待 {{ event.nativeFrameCounts.pending }} · 失败 {{ event.nativeFrameCounts.failed }} · 无法处理 {{ event.nativeFrameCounts.notApplicable }}</div>
      <div v-else-if="event.symbolicationStatus === 'pending'" class="notice">
        {{ symbolicationReason(event.symbolicationReason) }}。{{ event.crashKind === 'native' ? '以下为待处理的原始 Native 现场。' : '以下为待处理的脱敏原始异常链。' }}
      </div>
      <div v-else-if="event.symbolicationStatus === 'failed'" class="notice notice-warning">
        {{ symbolicationReason(event.symbolicationReason) }}，已保留脱敏原始异常链；上传新的 mapping 可触发重试。
      </div>

      <section class="panel detail-card" style="margin-bottom: 20px">
        <div class="panel-header" style="padding: 0 0 17px">
          <div>
            <h2>事件元数据</h2>
            <p>App {{ event.appId }} · 包名 {{ event.packageName }}</p>
          </div>
          <span class="badge" :class="event.symbolicationStatus === 'symbolicated' ? 'badge-success' : 'badge-warning'">
            {{ { pending: '待处理', failed: '还原失败', symbolicated: '已还原' }[event.symbolicationStatus] }}
          </span>
        </div>
        <dl class="meta-grid">
          <div class="meta-item"><dt>发生时间</dt><dd>{{ formatDateTime(event.occurredAt) }}</dd></div>
          <div class="meta-item"><dt>接收时间</dt><dd>{{ formatDateTime(event.receivedAt) }}</dd></div>
          <div class="meta-item"><dt>崩溃类型</dt><dd>{{ event.crashKind === 'native' ? 'Native' : 'JVM' }}</dd></div>
          <div class="meta-item"><dt>异常类型</dt><dd>{{ event.exceptionType || '未知异常' }}</dd></div>
          <div class="meta-item"><dt>应用版本</dt><dd>{{ event.appVersion }}（{{ event.versionCode ?? '—' }}）</dd></div>
          <div class="meta-item"><dt>构建</dt><dd>{{ event.buildId || '—' }}</dd></div>
          <div class="meta-item"><dt>渠道 / 环境</dt><dd>{{ event.channel || '—' }} / {{ event.environment || '—' }}</dd></div>
          <div class="meta-item"><dt>Android / 设备</dt><dd>{{ event.osVersion || '—' }} / {{ event.deviceModel || '—' }}</dd></div>
          <div class="meta-item"><dt>网络类型</dt><dd>{{ event.networkType || '—' }}</dd></div>
          <div class="meta-item"><dt>指纹</dt><dd class="fingerprint" :title="event.fingerprint">{{ event.fingerprint }}</dd></div>
          <div class="meta-item"><dt>指纹版本</dt><dd>{{ event.fingerprintVersion || '—' }}</dd></div>
          <template v-if="event.crashKind === 'jvm'">
            <div class="meta-item"><dt>符号表版本</dt><dd>{{ event.symbolFileRevision ? `revision ${event.symbolFileRevision}` : '—' }}</dd></div>
            <div class="meta-item"><dt>实际符号表 ID</dt><dd>{{ event.symbolFileId ?? '—' }}</dd></div>
            <div class="meta-item"><dt>实际 SHA-256</dt><dd class="fingerprint">{{ event.symbolFileSha256 ?? '—' }}</dd></div>
            <div class="meta-item"><dt>处理时间</dt><dd>{{ event.processedAt ? formatDateTime(event.processedAt) : '—' }}</dd></div>
            <div class="meta-item"><dt>处理原因</dt><dd>{{ symbolicationReason(event.symbolicationReason) }}</dd></div>
          </template>
          <div class="meta-item"><dt>归组</dt><dd>{{ event.groupingKind === 'temporary' ? `临时问题 · ${event.buildId}` : '正式问题' }}</dd></div>
        </dl>
        <EventIdentityFields
          :anonymous-device-id="event.anonymousDeviceId"
          :session-id="event.sessionId"
          :process-id="event.processId"
        />
      </section>

      <div v-if="event.diagnostics?.coreTruncation?.truncated" class="notice notice-warning">{{ event.crashKind === 'native' ? '主崩溃信息' : '主异常' }}在设备端因大小限制被裁剪，以下堆栈可能不完整。</div>
      <DetailTabs v-model="activeTab" :tabs="detailTabs" id-prefix="crash-detail" />
      <div v-show="activeTab === 'stack'" id="crash-detail-panel-stack" class="detail-tab-panel" role="tabpanel" aria-labelledby="crash-detail-tab-stack" tabindex="0">
        <section v-if="event.stack.format === 'retrace_text'" class="panel detail-card symbolicated-panel">
          <div class="panel-header" style="padding: 0 0 16px"><div><h2>还原堆栈</h2><p>首次成功结果已固定。完整保留内联与候选；成功不保证每一帧都准确。</p></div></div>
          <pre class="symbolicated-stack">{{ event.stack.content }}</pre>
        </section>
        <NativeCrashPanel :app-id="appId" :app-version="event.appVersion" :build-id="event.buildId" :can-manage="canManageAnalysis" v-else-if="event.stack.format === 'native_stack'" :crash="event.stack.content" />
        <StackTrace v-else :crash="event.stack.content" />
        <div v-if="event.crashKind === 'jvm' && canManageAnalysis && event.symbolicationStatus !== 'symbolicated'" class="detail-actions"><RouterLink class="button" :to="{ name: 'app-symbols', params: { appId }, query: { type: 'MAPPING', appVersion: event.appVersion, buildId: event.buildId } }">上传此版本的 mapping</RouterLink></div>
      </div>
      <DiagnosticThreads v-show="activeTab === 'threads'" :key="`${appId}:${eventId}`" id="crash-detail-panel-threads" role="tabpanel" aria-labelledby="crash-detail-tab-threads" tabindex="0" :diagnostics="event.diagnostics" :app-id="appId" :app-version="event.appVersion" :build-id="event.buildId" :can-manage="canManageAnalysis" :native-crash="event.stack.format === 'native_stack' ? event.stack.content : null" />
      <DiagnosticLogcat v-show="activeTab === 'logcat'" id="crash-detail-panel-logcat" role="tabpanel" aria-labelledby="crash-detail-tab-logcat" tabindex="0" :logcat="event.diagnostics?.logcat ?? null" />
      <DiagnosticMemory v-show="activeTab === 'memory'" id="crash-detail-panel-memory" role="tabpanel" aria-labelledby="crash-detail-tab-memory" tabindex="0" :memory="event.diagnostics?.memory ?? null" />
      <DiagnosticOpenFiles v-show="activeTab === 'openFiles'" id="crash-detail-panel-openFiles" role="tabpanel" aria-labelledby="crash-detail-tab-openFiles" tabindex="0" :open-files="event.diagnostics?.openFiles ?? null" />
      <DiagnosticProcess v-show="activeTab === 'process'" id="crash-detail-panel-process" role="tabpanel" aria-labelledby="crash-detail-tab-process" tabindex="0" :process="event.diagnostics?.process ?? null" :device="event.diagnostics?.device ?? null" />
      <CrashAnalysisPanel v-if="event.crashKind === 'jvm' && event.fatal" v-show="activeTab === 'analysis'" id="crash-detail-panel-analysis" role="tabpanel" aria-labelledby="crash-detail-tab-analysis" tabindex="0" :app-id="appId" :event-id="eventId" :can-create="canAnalyze" :can-manage="canManageAnalysis" :user-id="session.user?.id ?? null" />
    </template>
  </AppLayout>
</template>
