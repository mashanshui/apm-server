<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppLayout from '../components/AppLayout.vue'
import StatusMessage from '../components/StatusMessage.vue'
import { ApiError, errorMessage } from '../api/http'
import { symbolApi } from '../api/symbolApi'
import { useAppStore } from '../stores/apps'
import type { SymbolFileMetadata } from '../types/symbol'

const route = useRoute()
const router = useRouter()
const apps = useAppStore()
const appId = computed(() => String(route.params.appId))
const app = computed(() => apps.apps.find(item => item.appId === appId.value) ?? apps.currentApp)
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

/** 加载当前应用的符号表列表并隔离旧应用响应。 */
async function load(cursor?: string) {
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
    await symbolApi.upload(requestedAppId, buildId.value.trim(), uploadFile, operationController.signal)
    if (operationController.signal.aborted || appId.value !== requestedAppId) return
    success.value = 'mapping 上传成功，Crash 下次查看生效；卡顿仅影响后续上传事件。'
    resetUpload()
    await load()
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
  if (!window.confirm(`确认替换构建 ${currentConflict.buildId} 的 mapping？Crash 下次查看使用新版本，卡顿只影响后续上传。`)) return
  const requestedAppId = appId.value
  const operationController = new AbortController()
  uploadController = operationController
  uploading.value = true
  error.value = null
  try {
    await symbolApi.replace(requestedAppId, currentConflict.symbolId, currentConflict.revision,
      replacementFile, operationController.signal)
    if (operationController.signal.aborted || appId.value !== requestedAppId) return
    success.value = 'mapping 已替换并记录版本审计。'
    conflict.value = null
    conflictFile.value = null
    conflictFileSha256.value = null
    resetUpload()
    await load()
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
        <div class="field"><label for="symbol-build-id">buildId</label><input id="symbol-build-id" v-model="buildId" maxlength="128" pattern="[A-Za-z0-9._-]{1,128}" placeholder="例如 1.2.3-release" required><small>Crash 详情会按事件中的 buildId 查找；卡顿在上传解析时使用。</small></div>
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
      <div v-else class="table-wrap"><table class="data-table symbol-files-table"><thead><tr><th>buildId</th><th>文件</th><th>版本</th><th>大小</th><th>SHA-256</th><th>上传人</th><th>更新时间</th></tr></thead><tbody><tr v-for="item in items" :key="item.symbolId"><td><code>{{ item.buildId }}</code></td><td>{{ item.originalFilename }}</td><td>revision {{ item.revision }}</td><td>{{ formatBytes(item.sizeBytes) }}</td><td><code class="symbol-hash">{{ item.sha256 }}</code></td><td>{{ item.uploadedBy }}</td><td>{{ new Date(item.updatedAt).toLocaleString('zh-CN') }}</td></tr></tbody></table></div>
      <div v-if="nextCursor" class="form-actions symbol-pagination"><button class="button" type="button" :disabled="loading || loadingMore" @click="loadNextPage">{{ loadingMore ? '正在加载…' : '加载更多' }}</button></div>
    </section>
  </AppLayout>
</template>
