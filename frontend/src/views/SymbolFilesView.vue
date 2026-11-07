<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppLayout from '../components/AppLayout.vue'
import StatusMessage from '../components/StatusMessage.vue'
import { ApiError, errorMessage } from '../api/http'
import { symbolApi } from '../api/symbolApi'
import { useAppStore } from '../stores/apps'
import type { SymbolFileMetadata, SymbolicationProgress } from '../types/symbol'

const route = useRoute()
const router = useRouter()
const apps = useAppStore()
const appId = computed(() => String(route.params.appId))
const app = computed(() => apps.apps.find(item => item.appId === appId.value))
const canEdit = computed(() => app.value?.role === 'OWNER' || app.value?.role === 'ADMIN')
const items = ref<SymbolFileMetadata[]>([])
const loading = ref(false)
const uploading = ref(false)
const error = ref<string | null>(null)
const success = ref<string | null>(null)
const buildId = ref('')
const filterBuildId = ref('')
const selectedFile = ref<File | null>(null)
const conflict = ref<SymbolFileMetadata | null>(null)
const conflictFile = ref<File | null>(null)
/** 冲突上传文件在浏览器计算出的摘要。 */
const conflictFileSha256 = ref<string | null>(null)
/** 当前列表下一页的不透明游标。 */
const nextCursor = ref<string | null>(null)
/** 是否正在追加加载下一页。 */
const loadingMore = ref(false)
let requestToken = 0
let controller: AbortController | null = null
/** 正在执行的上传或替换请求控制器。 */
let uploadController: AbortController | null = null
/** 同时只跟踪一份活动 mapping，最多轮询五分钟。 */
const progressSymbol = ref<SymbolFileMetadata | null>(null)
/** 本轮累计计数与状态，不冒充全量事件统计。 */
const progress = ref<SymbolicationProgress | null>(null)
/** 进度请求的安全错误。 */
const progressError = ref<string | null>(null)
/** 切换、删除、替换和离页均使旧进度失效。 */
let progressGeneration = 0
/** 进度请求可主动取消。 */
let progressController: AbortController | null = null
/** 无重叠的五秒间隔轮询。 */
let progressTimer: ReturnType<typeof setTimeout> | null = null
/** 当前页面轮询次数上限。 */
let progressAttempts = 0
/** 后端五态中文说明。 */
const progressLabels = { queued: '已排队', running: '处理中', waiting_mapping: '等待 mapping', retry_wait: '退避等待', idle: '本轮空闲' }

/** 取消在途进度并使不服从 Abort 的迟到响应无效。 */
function stopProgress() {
  progressGeneration += 1
  progressController?.abort()
  if (progressTimer) clearTimeout(progressTimer)
  progressTimer = null
  progress.value = null
  progressSymbol.value = null
  progressError.value = null
}

/** 查询单个构建，只有当前请求能续约下一次轮询。 */
async function pollProgress(generation: number) {
  const symbol = progressSymbol.value
  if (!symbol || generation !== progressGeneration) return
  const requestedAppId = appId.value
  const operation = new AbortController()
  progressController = operation
  progressAttempts += 1
  try {
    const result = await symbolApi.progress(requestedAppId, symbol.symbolId, operation.signal)
    if (generation !== progressGeneration || operation.signal.aborted || requestedAppId !== appId.value) return
    progress.value = result
    if (['queued', 'running', 'retry_wait'].includes(result.state) && progressAttempts < 60) {
      progressTimer = setTimeout(() => { void pollProgress(generation) }, 5000)
    }
  } catch (failure) {
    if (generation === progressGeneration && !operation.signal.aborted) progressError.value = errorMessage(failure)
  } finally {
    if (progressController === operation) progressController = null
  }
}

/** 用户选择或刚上传的活动 mapping 开启一个有界轮询周期。 */
function trackProgress(symbol: SymbolFileMetadata) {
  stopProgress()
  progressSymbol.value = symbol
  progressAttempts = 0
  void pollProgress(progressGeneration)
}

/** 删除带条件 revision，失败或冲突刷新列表后要求重新确认。 */
async function remove(symbol: SymbolFileMetadata) {
  if (!canEdit.value || uploading.value || !window.confirm(`确认删除构建 ${symbol.buildId} 的 revision ${symbol.revision}？已有成功 Crash 保持固定；新批次将等待 mapping。`)) return
  const requestedAppId = appId.value
  const operation = new AbortController()
  uploadController = operation
  uploading.value = true
  stopProgress()
  error.value = null
  success.value = null
  try {
    await symbolApi.remove(requestedAppId, symbol.symbolId, symbol.revision, operation.signal)
    if (operation.signal.aborted || appId.value !== requestedAppId) return
    success.value = 'mapping 已删除并保留审计。重新上传将创建新符号表；已有成功结果保持固定。'
    await load()
  } catch (failure) {
    if (operation.signal.aborted || appId.value !== requestedAppId) return
    const message = failure instanceof ApiError && failure.status === 409 ? '符号表版本已变化，请刷新列表后重新确认。' : errorMessage(failure)
    await load()
    error.value = message
  } finally {
    if (uploadController === operation) { uploadController = null; uploading.value = false }
  }
}

/** 加载当前应用的符号表列表并隔离旧应用响应。 */
async function load(cursor?: string) {
  stopProgress()
  const token = ++requestToken
  const requestedAppId = appId.value
  const requestedBuildId = filterBuildId.value.trim() || undefined
  controller?.abort()
  controller = new AbortController()
  if (cursor) {
    loadingMore.value = true
  } else {
    loading.value = true
    items.value = []
    nextCursor.value = null
  }
  error.value = null
  try {
    const page = await symbolApi.list(requestedAppId, { buildId: requestedBuildId, cursor }, controller.signal)
    if (token !== requestToken || appId.value !== requestedAppId) return
    items.value = cursor ? [...items.value, ...page.items] : page.items
    nextCursor.value = page.nextCursor
  } catch (requestError) {
    if (token === requestToken && !(requestError instanceof DOMException && requestError.name === 'AbortError')) {
      error.value = errorMessage(requestError)
    }
  } finally {
    if (token === requestToken) {
      loading.value = false
      loadingMore.value = false
    }
  }
}

/** 保存文件选择并清除上一轮上传提示。 */
function selectFile(event: Event) {
  const input = event.target as HTMLInputElement
  selectedFile.value = input.files?.[0] ?? null
  success.value = null
  error.value = null
}

/** 按 buildId 重新查询当前应用的 mapping 元数据。 */
function applyFilter() {
  void load()
}

/** 使用当前筛选条件加载下一页，并追加到现有列表。 */
function loadNextPage() {
  if (!nextCursor.value || loading.value || loadingMore.value) return
  void load(nextCursor.value)
}

/** 从冲突错误的 errors 数组中读取服务端当前版本元数据。 */
function currentMetadataFromError(requestError: unknown): SymbolFileMetadata | null {
  if (!(requestError instanceof ApiError) || !Array.isArray(requestError.details)) return null
  const value = requestError.details[0] as Partial<SymbolFileMetadata> | undefined
  if (!value || typeof value.symbolId !== 'string' || typeof value.buildId !== 'string'
    || typeof value.revision !== 'number' || typeof value.sha256 !== 'string') return null
  return value as SymbolFileMetadata
}

/** 在浏览器中计算冲突文件的 SHA-256，以便替换前核对新旧摘要。 */
async function sha256(file: File): Promise<string> {
  const digest = await window.crypto.subtle.digest('SHA-256', await file.arrayBuffer())
  return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('')
}

/** 上传首次 mapping，冲突时进入显式确认流程。 */
async function upload() {
  const uploadFile = selectedFile.value
  if (!canEdit.value || uploading.value || !buildId.value.trim() || !uploadFile) return
  const requestedAppId = appId.value
  const operationController = new AbortController()
  uploadController = operationController
  uploading.value = true
  error.value = null
  success.value = null
  try {
    const uploaded = await symbolApi.upload(requestedAppId, buildId.value.trim(), uploadFile, operationController.signal)
    if (operationController.signal.aborted || appId.value !== requestedAppId) return
    success.value = 'mapping 上传成功，后台任务已登记；还原完成情况请查看进度。已有成功结果保持固定。'
    resetUpload()
    await load()
    if (!operationController.signal.aborted && appId.value === requestedAppId) trackProgress(uploaded)
  } catch (requestError) {
    if (operationController.signal.aborted || appId.value !== requestedAppId) return
    if (requestError instanceof ApiError && requestError.code === 'SYMBOL_CONFLICT') {
      conflict.value = currentMetadataFromError(requestError)
        ?? items.value.find(item => item.buildId === buildId.value.trim())
        ?? null
      conflictFile.value = uploadFile
      conflictFileSha256.value = null
      if (!conflict.value) error.value = '该构建已有不同 mapping，请刷新列表后重试。'
      else {
        try {
          const incomingSha256 = await sha256(uploadFile)
          if (!operationController.signal.aborted && appId.value === requestedAppId) {
            conflictFileSha256.value = incomingSha256
          }
        } catch {
          if (!operationController.signal.aborted && appId.value === requestedAppId) {
            error.value = '浏览器无法计算新文件 SHA-256，请使用受支持的安全浏览器页面后重试。'
          }
        }
      }
    } else {
      error.value = errorMessage(requestError)
    }
  } finally {
    if (uploadController === operationController) {
      uploadController = null
      uploading.value = false
    }
  }
}

/** 在管理员确认后替换指定 revision。 */
async function replace() {
  const currentConflict = conflict.value
  const replacementFile = conflictFile.value
  if (!canEdit.value || !currentConflict || !replacementFile || uploading.value) return
  if (!window.confirm(`确认替换构建 ${currentConflict.buildId} 的 mapping？待处理与失败 Crash 将在后台使用新文件；已有成功结果保持固定，卡顿只影响后续上传。`)) return
  const requestedAppId = appId.value
  const operationController = new AbortController()
  uploadController = operationController
  uploading.value = true
  error.value = null
  try {
    const replaced = await symbolApi.replace(requestedAppId, currentConflict.symbolId, currentConflict.revision,
      replacementFile, operationController.signal)
    if (operationController.signal.aborted || appId.value !== requestedAppId) return
    success.value = 'mapping 已替换并登记后台任务；已有成功结果保持固定。'
    conflict.value = null
    conflictFile.value = null
    conflictFileSha256.value = null
    resetUpload()
    await load()
    if (!operationController.signal.aborted && appId.value === requestedAppId) trackProgress(replaced)
  } catch (requestError) {
    if (operationController.signal.aborted || appId.value !== requestedAppId) return
    const message = requestError instanceof ApiError && requestError.code === 'SYMBOL_VERSION_CONFLICT'
      ? '符号表版本已变化，请刷新列表后重新确认。'
      : errorMessage(requestError)
    await load()
    error.value = message
  } finally {
    if (uploadController === operationController) {
      uploadController = null
      uploading.value = false
    }
  }
}

/** 清空上传表单，但不影响列表。 */
function resetUpload() {
  buildId.value = ''
  selectedFile.value = null
  const input = document.getElementById('symbol-file') as HTMLInputElement | null
  if (input) input.value = ''
}

/** 将字节数格式化为用户可读文本。 */
function formatBytes(value: number): string {
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KiB`
  return `${(value / (1024 * 1024)).toFixed(1)} MiB`
}

/** 监听应用切换、取消旧请求，并在 Crash 详情带入 buildId 时预填上传表单。 */
watch(() => [appId.value, route.query.buildId], (current, previous) => {
  if (previous && current[0] !== previous[0]) {
    stopProgress()
    controller?.abort()
    requestToken += 1
    loading.value = false
    loadingMore.value = false
    uploadController?.abort()
    uploadController = null
    uploading.value = false
    items.value = []
    nextCursor.value = null
    conflict.value = null
    conflictFile.value = null
    conflictFileSha256.value = null
    selectedFile.value = null
    buildId.value = ''
    success.value = null
    error.value = null
  }
  if (typeof route.query.buildId === 'string') {
    buildId.value = route.query.buildId
  }
  void load()
}, { immediate: true })
onBeforeUnmount(() => {
  stopProgress()
  requestToken += 1
  controller?.abort()
  uploadController?.abort()
})
</script>

<template>
  <AppLayout :app-id="appId">
    <div class="breadcrumb"><RouterLink :to="{ name: 'app-settings', params: { appId } }">应用设置</RouterLink><span class="breadcrumb-separator">/</span><span>符号表管理</span></div>
    <header class="page-header page-header-roomy">
      <div><p class="eyebrow">ANDROID MAPPING</p><h1>符号表管理</h1><p class="subtitle">按 appId 与 buildId 精确管理 R8 / ProGuard mapping。网页上传不会自动接入构建流程。</p></div>
      <button class="button" type="button" @click="router.push({ name: 'app-settings', params: { appId } })">返回设置</button>
    </header>

    <StatusMessage v-if="error" kind="error" :message="error" @retry="load" />
    <div v-if="success" class="notice notice-success" role="status">{{ success }}</div>

    <section v-if="canEdit" class="panel symbol-upload-panel">
      <div class="panel-header"><div><h2>上传 mapping</h2><p>仅接受单个文本 mapping，最大 32 MiB；请使用与 buildId 对应的同次构建文件。</p></div></div>
      <form class="symbol-upload-form" @submit.prevent="upload">
        <div class="field"><label for="symbol-build-id">buildId</label><input id="symbol-build-id" v-model="buildId" maxlength="128" pattern="[A-Za-z0-9._-]{1,128}" placeholder="例如 1.2.3-release" required><small>Crash 后台按事件 buildId 处理；卡顿在上传解析时使用。</small></div>
        <div class="field"><label for="symbol-file">mapping 文件</label><input id="symbol-file" type="file" accept=".txt,text/plain" required @change="selectFile"><small>{{ selectedFile ? `${selectedFile.name} · ${formatBytes(selectedFile.size)}` : '请选择 mapping.txt' }}</small></div>
        <div v-if="uploading" class="symbol-upload-progress" role="progressbar" aria-label="mapping 校验与上传进度" aria-valuetext="正在校验并上传">
          <span>正在校验并上传；服务端校验完成前无法报告准确百分比。</span>
        </div>
        <div class="form-actions"><span class="readonly-note">替换已有构建时会先显示新旧 SHA-256 并要求确认。</span><button class="button button-primary" type="submit" :disabled="uploading || !buildId.trim() || !selectedFile">{{ uploading ? '校验并上传中…' : '上传 mapping' }}</button></div>
      </form>
    </section>

    <section v-if="conflict" class="panel symbol-conflict-panel" role="alert">
      <div class="panel-header"><div><h2>检测到不同 mapping</h2><p>请确认是否替换当前版本。确认前不会覆盖服务器文件。</p></div></div>
      <dl class="meta-grid"><div class="meta-item"><dt>构建</dt><dd>{{ conflict.buildId }}</dd></div><div class="meta-item"><dt>当前版本</dt><dd>revision {{ conflict.revision }}</dd></div><div class="meta-item"><dt>当前 SHA-256</dt><dd><code>{{ conflict.sha256 }}</code></dd></div><div class="meta-item"><dt>新文件</dt><dd>{{ conflictFile?.name }} · {{ formatBytes(conflictFile?.size ?? 0) }}</dd></div><div class="meta-item"><dt>新 SHA-256</dt><dd><code v-if="conflictFileSha256">{{ conflictFileSha256 }}</code><span v-else>正在计算…</span></dd></div></dl>
      <div class="form-actions"><button class="button" type="button" :disabled="uploading" @click="conflict = null; conflictFile = null">取消</button><button class="button button-primary" type="button" :disabled="uploading" @click="replace">确认替换</button></div>
    </section>

    <section v-if="progressSymbol" class="panel detail-card" aria-label="构建还原进度">
      <div class="panel-header"><div><h2>后台还原 · {{ progressSymbol.buildId }}</h2><p>最多每五秒查询一次，五分钟后停止。空闲表示本轮没有可处理工作，不能证明所有事件都已成功。</p></div><button class="button" @click="trackProgress(progressSymbol)">刷新进度</button></div>
      <p v-if="progressError" class="field-error" role="alert">{{ progressError }}</p>
      <p v-else-if="!progress">正在查询进度…</p>
      <template v-if="progress"><p>{{ progressLabels[progress.state] }} · 本轮成功确认 {{ progress.succeededCount }} · 失败尝试 {{ progress.failedCount }}</p><p v-if="progress.errorCategory">原因：{{ progress.errorCategory }}</p></template>
    </section>

    <section class="panel symbol-list-panel">
      <div class="panel-header"><div><h2>当前 mapping</h2><p>列表只显示元数据；文件内容不会通过网页下载。</p></div><span class="toolbar-count">{{ items.length }} 份</span></div>
      <form class="symbol-filter-form" @submit.prevent="applyFilter">
        <label for="symbol-filter-build-id">按 buildId 筛选</label>
        <input id="symbol-filter-build-id" v-model="filterBuildId" maxlength="128" pattern="[A-Za-z0-9._-]{0,128}" placeholder="留空显示全部">
        <button class="button" type="submit" :disabled="loading">筛选</button>
        <button v-if="filterBuildId" class="button" type="button" :disabled="loading" @click="filterBuildId = ''; applyFilter()">清除</button>
      </form>
      <div v-if="loading" class="loading-state"><p>正在加载符号表…</p></div>
      <div v-else-if="items.length === 0" class="empty-state"><p>当前应用还没有注册 mapping。</p></div>
      <div v-else class="table-wrap"><table class="data-table symbol-files-table"><thead><tr><th>buildId</th><th>文件</th><th>版本</th><th>大小</th><th>SHA-256</th><th>上传人</th><th>更新时间</th><th>操作</th></tr></thead><tbody><tr v-for="item in items" :key="item.symbolId"><td><code>{{ item.buildId }}</code></td><td>{{ item.originalFilename }}</td><td>revision {{ item.revision }}</td><td>{{ formatBytes(item.sizeBytes) }}</td><td><code class="symbol-hash">{{ item.sha256 }}</code></td><td>{{ item.uploadedBy }}</td><td>{{ new Date(item.updatedAt).toLocaleString('zh-CN') }}</td><td><button class="link-button" type="button" @click="trackProgress(item)">查看进度</button><button v-if="canEdit" class="link-button" type="button" :disabled="uploading" @click="remove(item)">删除</button></td></tr></tbody></table></div>
      <div v-if="nextCursor" class="form-actions symbol-pagination"><button class="button" type="button" :disabled="loading || loadingMore" @click="loadNextPage">{{ loadingMore ? '正在加载…' : '加载更多' }}</button></div>
    </section>
  </AppLayout>
</template>

<style scoped>
/* 增加进度和删除后，桌面列表保留可见操作列，长 UUID 与摘要在单元格内换行。 */
.symbol-files-table { min-width: 800px; table-layout: fixed; width: 100%; }
.symbol-files-table th, .symbol-files-table td { white-space: normal; overflow-wrap: anywhere; padding: 14px 10px; }
.symbol-files-table th:nth-child(5) { width: 18%; }
.symbol-files-table th:nth-child(6) { width: 14%; }
.symbol-files-table .link-button { display: block; margin: 4px 0; }
</style>
