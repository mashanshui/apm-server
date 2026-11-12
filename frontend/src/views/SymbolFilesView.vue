<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppLayout from '../components/AppLayout.vue'
import StatusMessage from '../components/StatusMessage.vue'
import { ApiError, errorMessage } from '../api/http'
import { symbolApi } from '../api/symbolApi'
import { useAppStore } from '../stores/apps'
import type { SymbolFileMetadata, SymbolicationProgress, NativeSnapshot, NativeExpected } from '../types/symbol'

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
/** 当前管理类型及所属应用版本，应用构建只适用于 mapping。 */
const symbolType = ref<'MAPPING' | 'NATIVE_SO'>('MAPPING')
/** 上传仅在用户操作或详情预填时打开，列表始终作为页面主区域。 */
const uploadOpen = ref(false)
/** 原生模态框提供焦点约束、Escape 关闭和返回触发按钮。 */
const uploadDialog = ref<HTMLDialogElement | null>(null)
/** 筛选区折叠只影响展示，保留当前筛选输入。 */
const filtersExpanded = ref(true)
const appVersion = ref('')
/** SO 目标来自详情模块，不作为文件内容证据。 */
const targetAbi = ref<string | undefined>()
const targetElfBuildId = ref<string | undefined>()
/** 完整批次冲突与原 ZIP，仅保留在当前页面请求周期。 */
const nativeConflict = ref<NativeSnapshot[] | null>(null)
const nativeConflictFile = ref<File | null>(null)
/** 当前列表的版本及范围筛选。 */
const filterVersion = ref('')
const filterScope = ref('')
const filterAbi = ref('')
const filterElf = ref('')
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
  if (symbol.type !== 'MAPPING' || !symbol.appVersion) return
  progressSymbol.value = symbol
  progressAttempts = 0
  void pollProgress(progressGeneration)
}

/** 删除带条件 revision，失败或冲突刷新列表后要求重新确认。 */
async function remove(symbol: SymbolFileMetadata) {
  if (!canEdit.value || uploading.value || !window.confirm(`确认删除 ${symbol.appVersion ?? '未登记版本'} / ${symbol.abi ?? symbol.buildId ?? '版本默认'} 的文件修订号 ${symbol.revision}？已有成功 Crash 保持固定，Native 原始现场不变。`)) return
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
    success.value = '文件已删除并保留审计；已有成功 Crash 结果保持固定，Native 原始现场不变。'
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
  const requestedBuildId = symbolType.value === 'MAPPING' ? filterBuildId.value || undefined : undefined
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
    const page = await symbolApi.list(requestedAppId, { type: symbolType.value, appVersion: filterVersion.value || undefined,
      mappingScope: symbolType.value === 'MAPPING' ? filterScope.value || undefined : undefined,
      abi: symbolType.value === 'NATIVE_SO' ? filterAbi.value || undefined : undefined,
      elfBuildId: symbolType.value === 'NATIVE_SO' ? filterElf.value || undefined : undefined,
      buildId: requestedBuildId, cursor }, controller.signal)
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
  if (!value || typeof value.symbolId !== 'string' || (value.buildId !== null && typeof value.buildId !== 'string')
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
  if (symbolType.value === 'NATIVE_SO') { await uploadNative(); return }
  if (!canEdit.value || uploading.value || !appVersion.value || !uploadFile) return
  const requestedAppId = appId.value
  const operationController = new AbortController()
  uploadController = operationController
  uploading.value = true
  error.value = null
  success.value = null
  try {
    const uploaded = await symbolApi.upload(requestedAppId, appVersion.value, buildId.value || null, uploadFile, operationController.signal)
    if (operationController.signal.aborted || appId.value !== requestedAppId) return
    success.value = 'mapping 上传成功，后台任务已登记；还原完成情况请查看进度。已有成功结果保持固定。'
    resetUpload()
    uploadOpen.value = false
    await load()
    if (!operationController.signal.aborted && appId.value === requestedAppId) trackProgress(uploaded)
  } catch (requestError) {
    if (operationController.signal.aborted || appId.value !== requestedAppId) return
    if (requestError instanceof ApiError && requestError.code === 'SYMBOL_CONFLICT') {
      conflict.value = currentMetadataFromError(requestError)
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

/** SO 上传与确认始终重新提交同一 ZIP，全批成功后只报告文件已保存。 */
async function uploadNative(expected?: NativeExpected[]) {
  const file = expected ? nativeConflictFile.value : selectedFile.value
  if (!canEdit.value || uploading.value || !appVersion.value || !file) return
  if (expected && !window.confirm('确认整批模块及各项新旧 SHA-256？任一身份变化都会使整批失败。')) return
  const requestedAppId = appId.value
  const operation = new AbortController()
  uploadController = operation
  uploading.value = true
  error.value = null
  success.value = null
  stopProgress()
  try {
    const result = await symbolApi.uploadNative(requestedAppId, appVersion.value, file,
      { abi: targetAbi.value, elfBuildId: targetElfBuildId.value }, expected, operation.signal)
    if (operation.signal.aborted || requestedAppId !== appId.value) return
    nativeConflict.value = null
    nativeConflictFile.value = null
    success.value = `SO 文件已保存：新建 ${result.createdCount}，替换 ${result.replacedCount}，相同 ${result.unchangedCount}。本期未提供 Native 还原。`
    resetUpload()
    uploadOpen.value = false
    await load()
  } catch (failure) {
    if (operation.signal.aborted || requestedAppId !== appId.value) return
    if (failure instanceof ApiError && failure.code === 'SYMBOL_BATCH_CONFLICT' && Array.isArray(failure.details)) {
      nativeConflict.value = failure.details as NativeSnapshot[]
      nativeConflictFile.value = file
    } else error.value = errorMessage(failure)
  } finally {
    if (uploadController === operation) { uploadController = null; uploading.value = false }
  }
}

/** 确认服务端快照中的全部身份，包括预期不存在项。 */
function confirmNative() {
  if (!nativeConflict.value) return
  void uploadNative(nativeConflict.value.map(item => ({ abi: item.abi, elfBuildId: item.elfBuildId,
    symbolId: item.current?.symbolId ?? null, revision: item.current?.revision ?? null })))
}

/** 取消请求并清除前一类型或事件的材料，旧响应不能覆盖新选择。 */
function clearContext() {
  uploadOpen.value = false
  stopProgress()
  controller?.abort()
  requestToken += 1
  uploadController?.abort()
  uploadController = null
  uploading.value = false
  conflict.value = null
  conflictFile.value = null
  conflictFileSha256.value = null
  nativeConflict.value = null
  nativeConflictFile.value = null
  selectedFile.value = null
  buildId.value = ''
  targetAbi.value = undefined
  targetElfBuildId.value = undefined
  filterBuildId.value = ''
  filterScope.value = ''
  filterAbi.value = ''
  filterElf.value = ''
  success.value = null
  error.value = null
  const input = document.getElementById('symbol-file') as HTMLInputElement | null
  if (input) input.value = ''
}

/** 标签页切换保留版本输入，并清空前一类型的文件、目标和冲突。 */
function changeType(type: 'MAPPING' | 'NATIVE_SO') {
  if (symbolType.value === type) return
  symbolType.value = type
  clearContext()
  void load()
}

/** 键盘左右切换标签，并将焦点移动到新的活动标签。 */
function moveTab() {
  changeType(symbolType.value === 'MAPPING' ? 'NATIVE_SO' : 'MAPPING')
  document.getElementById(`symbol-tab-${symbolType.value}`)?.focus()
}

/** 从列表打开上传弹窗，默认沿用正在查看的版本。 */
function openUpload() {
  if (!canEdit.value) return
  if (!appVersion.value) appVersion.value = filterVersion.value
  error.value = null
  uploadOpen.value = true
}

/** 上传期间保留弹窗；关闭时清理文件与确认快照，防止误复用。 */
function closeUpload() {
  if (uploading.value) return
  uploadOpen.value = false
  conflict.value = null
  conflictFile.value = null
  conflictFileSha256.value = null
  nativeConflict.value = null
  nativeConflictFile.value = null
  error.value = null
  resetUpload()
}

/** 清空全部列表筛选，不改动上传所用版本或模块目标。 */
function resetFilters() {
  filterVersion.value = ''
  filterBuildId.value = ''
  filterScope.value = ''
  filterAbi.value = ''
  filterElf.value = ''
  void load()
}

/** 格式化列表更新时间，以紧凑的日期和时间两行呈现。 */
function formatTime(value: string, part: 'date' | 'time'): string {
  /** 使用浏览器当前时区，与原列表时间语义一致。 */
  const date = new Date(value)
  return part === 'date' ? date.toLocaleDateString('zh-CN') : date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

/** 将状态同步到原生 dialog；异步渲染后再次检查，避免关闭后误打开。 */
watch(uploadOpen, async open => {
  await nextTick()
  if (open !== uploadOpen.value || !uploadDialog.value) return
  if (open && !uploadDialog.value.open) uploadDialog.value.showModal()
  else if (!open && uploadDialog.value.open) uploadDialog.value.close()
})

/** 应用列表异步加载后，详情预填仍可打开授权用户的上传弹窗。 */
watch(canEdit, allowed => {
  if (allowed && typeof route.query.appVersion === 'string') openUpload()
  else if (!allowed) clearContext()
})

/** 在管理员确认后替换指定 revision。 */
async function replace() {
  const currentConflict = conflict.value
  const replacementFile = conflictFile.value
  if (!canEdit.value || !currentConflict || !replacementFile || uploading.value) return
  if (!window.confirm(`确认替换 ${currentConflict.appVersion} / ${currentConflict.buildId ?? '版本默认'} 的 mapping？待处理与失败 Crash 将在后台选择适用文件；已有成功结果保持固定，卡顿只影响后续上传。`)) return
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
    uploadOpen.value = false
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

/** 路由预填属于当前应用及事件；同应用切换事件也使旧请求失效。 */
watch(() => [appId.value, route.query.type, route.query.appVersion, route.query.buildId,
  route.query.targetAbi, route.query.targetElfBuildId], () => {
  clearContext()
  symbolType.value = route.query.type === 'NATIVE_SO' ? 'NATIVE_SO' : 'MAPPING'
  appVersion.value = typeof route.query.appVersion === 'string' ? route.query.appVersion : ''
  filterVersion.value = appVersion.value
  if (symbolType.value === 'MAPPING') buildId.value = typeof route.query.buildId === 'string' ? route.query.buildId : ''
  else if (typeof route.query.targetAbi === 'string' && typeof route.query.targetElfBuildId === 'string') {
    targetAbi.value = route.query.targetAbi
    targetElfBuildId.value = route.query.targetElfBuildId
  }
  uploadOpen.value = canEdit.value && typeof route.query.appVersion === 'string'
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
    <header class="page-header symbol-page-heading">
      <div><h1>符号表管理</h1><p class="subtitle">按应用版本管理符号文件，为崩溃排查准备准确的符号来源。</p></div>
      <button class="button" type="button" @click="router.push({ name: 'app-settings', params: { appId } })">返回设置</button>
    </header>
    <StatusMessage v-if="error && !uploadOpen" kind="error" :message="error" @retry="load" />
    <div v-if="success" class="notice notice-success" role="status">{{ success }}</div>

    <section class="panel symbol-workspace" aria-label="符号文件管理">
      <div class="symbol-tabs" role="tablist" aria-label="符号类型">
        <button id="symbol-tab-MAPPING" type="button" role="tab" :aria-selected="symbolType === 'MAPPING'" :tabindex="symbolType === 'MAPPING' ? 0 : -1" aria-controls="symbol-content" @click="changeType('MAPPING')" @keydown.left.prevent="moveTab" @keydown.right.prevent="moveTab">mapping 符号表</button>
        <button id="symbol-tab-NATIVE_SO" type="button" role="tab" :aria-selected="symbolType === 'NATIVE_SO'" :tabindex="symbolType === 'NATIVE_SO' ? 0 : -1" aria-controls="symbol-content" @click="changeType('NATIVE_SO')" @keydown.left.prevent="moveTab" @keydown.right.prevent="moveTab">SO 符号表</button>
      </div>
      <div id="symbol-content" role="tabpanel" :aria-labelledby="`symbol-tab-${symbolType}`">
        <form class="symbol-filter-form" @submit.prevent="applyFilter">
          <div class="symbol-filter-heading"><span>筛选条件</span><button type="button" class="link-button" :aria-expanded="filtersExpanded" aria-controls="symbol-filter-fields" @click="filtersExpanded = !filtersExpanded">{{ filtersExpanded ? '收起筛选' : '展开筛选' }} <span aria-hidden="true">{{ filtersExpanded ? '⌃' : '⌄' }}</span></button></div>
          <div v-show="filtersExpanded" id="symbol-filter-fields" class="symbol-filter-fields">
            <div class="field"><label for="symbol-filter-version">应用版本</label><input id="symbol-filter-version" v-model="filterVersion" maxlength="128" placeholder="全部版本"></div>
            <template v-if="symbolType === 'MAPPING'">
              <div class="field"><label for="symbol-filter-build-id">构建标识</label><input id="symbol-filter-build-id" v-model="filterBuildId" maxlength="256" placeholder="输入完整 buildId"></div>
              <div class="field"><label for="symbol-filter-scope">适用范围</label><select id="symbol-filter-scope" v-model="filterScope" aria-label="mapping 范围"><option value="">全部范围</option><option value="default">版本默认</option><option value="build">构建专用</option></select></div>
            </template>
            <template v-else>
              <div class="field"><label for="symbol-filter-abi">CPU 架构</label><input id="symbol-filter-abi" v-model="filterAbi" placeholder="例如 arm64-v8a" aria-label="ABI 筛选"></div>
              <div class="field"><label for="symbol-filter-elf">ELF Build ID</label><input id="symbol-filter-elf" v-model="filterElf" maxlength="128" placeholder="输入完整 ELF Build ID" aria-label="ELF Build ID 筛选"></div>
            </template>
          </div>
          <div class="symbol-filter-actions"><span class="symbol-filter-note">{{ symbolType === 'MAPPING' ? '按版本和构建精确查找，默认文件与专用文件独立管理。' : '模块身份由 CPU 架构与完整 ELF Build ID 确定。' }}</span><button class="button" type="button" :disabled="loading" @click="resetFilters">重置</button><button class="button button-primary" type="submit" :disabled="loading">{{ loading ? '查询中…' : '查询' }}</button></div>
        </form>
        <div class="symbol-list-panel">
          <div class="symbol-list-heading"><div class="symbol-list-title"><h2>符号表列表</h2><span class="symbol-count">已加载 {{ items.length }} 份</span></div><button v-if="canEdit" class="button button-primary symbol-upload-trigger" type="button" @click="openUpload"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M12 16V4m-4 4 4-4 4 4M4 15v5h16v-5" /></svg>上传符号表</button><span v-else class="symbol-readonly">只读权限</span></div>
          <p class="symbol-capability-note">{{ symbolType === 'MAPPING' ? '构建专用优先，缺失时使用同版本默认 mapping。上传成功后，可查看版本还原进度。' : 'SO 仅用于文件管理，本期未提供 Native 地址还原；保存文件不会改变事件状态。' }}</p>
          <div v-if="loading" class="loading-state"><p>正在加载符号表…</p></div>
          <div v-else-if="items.length === 0" class="empty-state symbol-empty"><svg width="40" height="40" viewBox="0 0 40 40" fill="none" stroke="currentColor" stroke-width="1.4" aria-hidden="true"><path d="M11 5h13l7 7v23H11zM24 5v8h7M16 20h10m-10 6h7" /></svg><h3>暂无符合条件的符号表</h3><p>当前筛选没有符号文件。</p><p class="symbol-empty-help">{{ canEdit ? '调整筛选条件，或点击右上角上传符号表。' : '请调整筛选条件，或联系应用管理员上传。' }}</p></div>
          <div v-else class="table-wrap"><table class="data-table symbol-files-table"><thead><tr><th class="symbol-version-col">应用版本</th><th class="symbol-scope-col">{{ symbolType === 'MAPPING' ? '适用范围 / 构建标识' : 'CPU 架构' }}</th><th class="symbol-file-col">{{ symbolType === 'MAPPING' ? '符号文件' : '模块 / 符号文件' }}</th><th v-if="symbolType === 'NATIVE_SO'" class="symbol-elf-col">ELF Build ID</th><th class="symbol-time-col">更新时间</th><th class="symbol-actions-col">操作</th></tr></thead><tbody>
            <tr v-for="item in items" :key="item.symbolId">
              <td><strong v-if="item.appVersion" class="symbol-version">{{ item.appVersion }}</strong><template v-else><span class="symbol-badge symbol-badge-warning">未登记版本</span><small class="symbol-secondary">不匹配事件，需重新上传</small></template></td>
              <td><template v-if="item.type === 'MAPPING'"><span class="symbol-badge" :class="{ 'symbol-badge-default': item.appVersion && !item.buildId }">{{ item.appVersion ? item.buildId ? '构建专用' : '版本默认' : '旧登记' }}</span><code v-if="item.buildId" class="symbol-build">{{ item.buildId }}</code><small v-else class="symbol-secondary">无专用登记时使用</small></template><span v-else class="symbol-badge">{{ item.abi }}</span></td>
              <td><strong class="symbol-filename">{{ item.originalFilename }}</strong><span class="symbol-secondary">{{ formatBytes(item.sizeBytes) }}<span class="symbol-dot">·</span>文件修订号 {{ item.revision }}</span><small v-if="item.type === 'NATIVE_SO'" class="symbol-secondary">函数符号 {{ item.hasFunctionSymbols ? '有' : '无' }} · 调试信息 {{ item.hasDebugInfo ? '有' : '无' }} · 行信息 {{ item.hasDebugLine ? '有' : '无' }}</small><details class="symbol-file-details"><summary>文件详情</summary><dl><dt>SHA-256</dt><dd><code>{{ item.sha256 }}</code></dd><dt>上传人</dt><dd>{{ item.uploadedBy }}</dd></dl></details></td>
              <td v-if="symbolType === 'NATIVE_SO'"><code class="symbol-elf">{{ item.elfBuildId }}</code></td>
              <td><span class="symbol-date">{{ formatTime(item.updatedAt, 'date') }}</span><small class="symbol-secondary">{{ formatTime(item.updatedAt, 'time') }}</small></td>
              <td><div class="symbol-row-actions"><button v-if="item.type === 'MAPPING' && item.appVersion" class="link-button" type="button" @click="trackProgress(item)">查看进度</button><button v-if="canEdit" class="link-button symbol-delete" type="button" :disabled="uploading" @click="remove(item)">删除</button><span v-if="!canEdit && (item.type !== 'MAPPING' || !item.appVersion)" class="symbol-secondary">—</span></div></td>
            </tr>
          </tbody></table></div>
          <div v-if="!loading" class="symbol-list-footer"><span>{{ nextCursor ? '还有更多文件，可继续加载' : `已显示全部 ${items.length} 份文件` }}</span><button v-if="nextCursor" class="button symbol-pagination" type="button" :disabled="loading || loadingMore" @click="loadNextPage">{{ loadingMore ? '正在加载…' : '加载更多' }}</button></div>
        </div>
      </div>
    </section>
        <section v-if="progressSymbol" class="panel detail-card" aria-label="构建还原进度">
      <div class="panel-header"><div><h2>版本本轮还原 · {{ progressSymbol.appVersion }}</h2><p>计数涵盖本版本所有构建，并非当前文件独有。最多每五秒查询一次，五分钟后停止。空闲表示本轮没有可处理工作，不能证明所有事件都已成功。</p></div><button class="button" @click="trackProgress(progressSymbol)">刷新进度</button></div>
      <p v-if="progressError" class="field-error" role="alert">{{ progressError }}</p>
      <p v-else-if="!progress">正在查询进度…</p>
      <template v-if="progress"><p>{{ progressLabels[progress.state] }} · 本轮成功确认 {{ progress.succeededCount }} · 失败尝试 {{ progress.failedCount }}</p><p v-if="progress.errorCategory">原因：{{ progress.errorCategory }}</p></template>
    </section>



    <dialog v-if="canEdit" ref="uploadDialog" class="symbol-upload-dialog" aria-labelledby="symbol-upload-title" @cancel.prevent="closeUpload">
      <header class="symbol-dialog-header"><div><span class="symbol-dialog-kind">{{ symbolType === 'MAPPING' ? 'JVM MAPPING' : 'NATIVE SO' }}</span><h2 id="symbol-upload-title">{{ symbolType === 'MAPPING' ? '上传 mapping 符号表' : '上传 SO 符号表' }}</h2></div><button class="symbol-dialog-close" type="button" aria-label="关闭上传弹窗" :disabled="uploading" @click="closeUpload">×</button></header>
      <div class="symbol-dialog-body">
        <StatusMessage v-if="error" kind="error" :message="error" />
        <section class="symbol-upload-panel">
          <p class="symbol-dialog-description">{{ symbolType === 'MAPPING' ? '填写应用版本并上传文本 mapping。构建标识留空时，登记为此版本默认文件。' : '上传包含 SO 的 ZIP 压缩包，服务端将从文件内容识别 CPU 架构和完整 ELF Build ID。' }}</p>
          <form class="symbol-upload-form" @submit.prevent="upload">
            <div class="symbol-upload-fields"><div class="field"><label for="symbol-app-version">应用版本 <span class="symbol-required">*</span></label><input id="symbol-app-version" v-model="appVersion" maxlength="128" required autofocus :disabled="uploading || !!conflict || !!nativeConflict" placeholder="与事件 appVersion 完全一致"></div><div v-if="symbolType === 'MAPPING'" class="field"><label for="symbol-build-id">buildId <span class="symbol-optional">选填</span></label><input id="symbol-build-id" v-model="buildId" maxlength="256" :disabled="uploading || !!conflict" placeholder="留空使用版本默认"><small>{{ buildId ? '构建专用 mapping，仅用于此构建。' : '此版本默认 mapping；专用登记不存在时使用。' }}</small></div></div>
            <p v-if="symbolType === 'NATIVE_SO' && targetAbi && targetElfBuildId" class="symbol-target">目标模块：<strong>{{ targetAbi }}</strong><code>{{ targetElfBuildId }}</code></p>
            <div class="field symbol-file-field"><label for="symbol-file">{{ symbolType === 'MAPPING' ? 'mapping 文件' : 'SO ZIP' }} <span class="symbol-required">*</span></label><div class="symbol-file-picker"><svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.4" aria-hidden="true"><path d="M12 15V3m-4 4 4-4 4 4M4 14v6h16v-6" /></svg><strong>{{ selectedFile ? selectedFile.name : '点击选择符号文件' }}</strong><span>{{ selectedFile ? formatBytes(selectedFile.size) : symbolType === 'MAPPING' ? '支持 .txt，最大 32 MiB' : '支持 .zip，最大 256 MiB' }}</span><input id="symbol-file" :key="symbolType" type="file" :accept="symbolType === 'MAPPING' ? '.txt,text/plain' : '.zip,application/zip'" required :disabled="uploading || !!conflict || !!nativeConflict" @change="selectFile"></div></div>
            <div v-if="uploading" class="symbol-upload-progress" role="progressbar" aria-label="符号文件校验与上传进度" aria-valuetext="正在校验并上传"><span>正在校验并上传，请稍候。校验完成前无法报告准确百分比。</span></div>
            <p class="symbol-upload-hint">{{ symbolType === 'MAPPING' ? '已有不同内容时，会展示新旧摘要并请你确认。保存成功后，后台处理尚未成功的 JVM 事件。' : 'ZIP 可包含多个模块与架构。发现冲突时需确认整批文件；本期未提供 Native 地址还原。' }}</p>
            <div v-if="!conflict && !nativeConflict" class="symbol-dialog-actions"><button class="button" type="button" :disabled="uploading" @click="closeUpload">取消</button><button class="button button-primary" type="submit" :disabled="uploading || !appVersion || !selectedFile">{{ uploading ? '校验并上传中…' : symbolType === 'MAPPING' ? '上传 mapping' : '上传 SO ZIP' }}</button></div>
          </form>
        </section>
            <section v-if="nativeConflict" class="panel detail-card symbol-native-conflict" role="alert"><h2>确认整批 SO</h2><p>以下全部身份将一起提交；任一预期变化都会拒绝整批。</p><div v-for="entry in nativeConflict" :key="entry.abi + entry.elfBuildId" class="meta-item"><strong>{{ entry.abi }} · {{ entry.elfBuildId }}</strong><p>{{ entry.current ? `替换或保持文件修订号 ${entry.current.revision}` : '新建（预期不存在）' }}</p><p>当前 SHA-256：{{ entry.current?.sha256 ?? '无' }}</p><p>传入 SHA-256：{{ entry.incomingSha256 }}</p></div><button class="button" :disabled="uploading" @click="nativeConflict = null; nativeConflictFile = null">取消</button><button class="button button-primary" :disabled="uploading" @click="confirmNative">确认整批</button></section>
    <section v-if="conflict" class="panel symbol-conflict-panel" role="alert">
      <div class="panel-header"><div><h2>检测到不同 mapping</h2><p>请确认是否替换当前版本。确认前不会覆盖服务器文件。</p></div></div>
      <dl class="meta-grid"><div class="meta-item"><dt>应用版本 / 范围</dt><dd>{{ conflict.appVersion }} / {{ conflict.buildId ?? '版本默认' }}</dd></div><div class="meta-item"><dt>当前版本</dt><dd>revision {{ conflict.revision }}</dd></div><div class="meta-item"><dt>当前 SHA-256</dt><dd><code>{{ conflict.sha256 }}</code></dd></div><div class="meta-item"><dt>新文件</dt><dd>{{ conflictFile?.name }} · {{ formatBytes(conflictFile?.size ?? 0) }}</dd></div><div class="meta-item"><dt>新 SHA-256</dt><dd><code v-if="conflictFileSha256">{{ conflictFileSha256 }}</code><span v-else>正在计算…</span></dd></div></dl>
      <div class="form-actions"><button class="button" type="button" :disabled="uploading" @click="conflict = null; conflictFile = null">取消</button><button class="button button-primary" type="button" :disabled="uploading" @click="replace">确认替换</button></div>
    </section>


      </div>
    </dialog>
  </AppLayout>
</template>

<style scoped>
.symbol-page-heading { margin-bottom: 24px; }
.symbol-workspace { overflow: hidden; margin-bottom: 20px; }
.symbol-tabs { display: flex; gap: 30px; padding: 0 26px; border-bottom: 1px solid var(--line); }
.symbol-tabs [role="tab"] { position: relative; border: 0; background: none; padding: 21px 0 18px; color: var(--muted-strong); font: inherit; font-size: 14px; font-weight: 600; cursor: pointer; }
.symbol-tabs [aria-selected="true"] { color: var(--primary); }
.symbol-tabs [aria-selected="true"]::after { content: ''; position: absolute; height: 3px; border-radius: 3px 3px 0 0; background: var(--primary); bottom: -1px; left: 0; right: 0; }
.symbol-filter-form { display: block; padding: 22px 26px 24px; border-bottom: 1px solid var(--line); background: #fafbfd; }
.symbol-filter-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 18px; color: var(--muted-strong); font-size: 12px; font-weight: 600; }
.symbol-filter-fields { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 22px; }
.symbol-filter-form .field { gap: 8px; min-width: 0; }
.symbol-filter-form input { min-width: 0; width: 100%; }
.symbol-filter-actions { display: flex; align-items: center; gap: 10px; margin-top: 20px; }
.symbol-filter-note { flex: 1; color: var(--muted); font-size: 12px; line-height: 1.6; }
.symbol-filter-actions .button { min-width: 76px; }
.symbol-list-panel { margin: 0; }
.symbol-list-heading { display: flex; justify-content: space-between; align-items: center; gap: 18px; padding: 24px 26px 10px; }
.symbol-list-title { display: flex; gap: 12px; align-items: center; }
.symbol-list-title h2 { margin: 0; font-size: 16px; }
.symbol-count { color: var(--muted); font-size: 12px; }
.symbol-upload-trigger { display: inline-flex; gap: 8px; align-items: center; }
.symbol-capability-note { margin: 0; padding: 0 26px 20px; color: var(--muted); font-size: 12px; line-height: 1.6; }
.symbol-files-table { min-width: 800px; table-layout: fixed; width: 100%; }
.symbol-files-table th, .symbol-files-table td { white-space: normal; overflow-wrap: anywhere; padding: 17px 20px; vertical-align: top; }
.symbol-files-table th { background: #f7f9fc; font-size: 11px; font-weight: 600; letter-spacing: 0; }
.symbol-version-col { width: 16%; }
.symbol-scope-col { width: 16%; }
.symbol-file-col { width: 28%; }
.symbol-elf-col { width: 23%; }
.symbol-time-col { width: 14%; }
.symbol-actions-col { width: 14%; }
.symbol-files-table:has(.symbol-elf-col) .symbol-version-col { width: 13%; }
.symbol-files-table:has(.symbol-elf-col) .symbol-scope-col { width: 13%; }
.symbol-files-table:has(.symbol-elf-col) .symbol-file-col { width: 24%; }
.symbol-files-table:has(.symbol-elf-col) .symbol-actions-col { width: 13%; }
.symbol-version, .symbol-filename { font-weight: 600; line-height: 1.6; display: block; }
.symbol-secondary { color: var(--muted); font-size: 11px; line-height: 1.7; display: block; margin-top: 4px; }
.symbol-dot { margin: 0 6px; }
.symbol-badge { display: inline-block; padding: 3px 8px; border-radius: 4px; background: #f0f3f7; color: #52647c; font-size: 11px; line-height: 1.6; }
.symbol-badge-default { color: #2869ba; background: #edf5ff; }
.symbol-badge-warning { color: #966622; background: #fff5de; }
.symbol-build { display: block; font-size: 11px; margin-top: 7px; line-height: 1.7; }
.symbol-elf { font-size: 11px; line-height: 1.8; color: var(--muted-strong); }
.symbol-date { white-space: nowrap; font-size: 12px; }
.symbol-row-actions { display: flex; flex-direction: column; align-items: flex-start; gap: 10px; padding-top: 2px; }
.symbol-row-actions .link-button { white-space: nowrap; font-size: 12px; }
.symbol-delete { color: #8b98a8; }
.symbol-delete:hover { color: #ce4451; }
.symbol-file-details { font-size: 11px; margin-top: 8px; }
.symbol-file-details summary { color: var(--primary); cursor: pointer; width: fit-content; }
.symbol-file-details dl { margin: 10px 0 0; padding: 10px; background: #f5f7fa; border-radius: 5px; }
.symbol-file-details dt { color: var(--muted); margin: 6px 0 3px; }
.symbol-file-details dd { margin: 0; line-height: 1.7; font-size: 10px; overflow-wrap: anywhere; }
.symbol-list-footer { padding: 17px 26px; display: flex; justify-content: space-between; align-items: center; gap: 12px; border-top: 1px solid var(--line); color: var(--muted); font-size: 12px; }
.symbol-empty { padding: 50px 24px; }
.symbol-empty svg { margin: 0 auto 14px; color: #9aafc8; }
.symbol-empty h3 { color: var(--muted-strong); font-size: 14px; margin: 0 0 10px; }
.symbol-empty-help { font-size: 12px; margin-top: 7px; }
.symbol-readonly { font-size: 12px; color: var(--muted); }
.symbol-upload-dialog { width: min(640px, calc(100vw - 48px)); max-height: calc(100vh - 64px); padding: 0; border: 1px solid var(--line); border-radius: 14px; box-shadow: 0 24px 90px #192f5140; color: var(--text); background: var(--surface, white); }
.symbol-upload-dialog::backdrop { background: #152c4b66; backdrop-filter: blur(3px); }
.symbol-dialog-header { position: sticky; top: 0; z-index: 1; display: flex; justify-content: space-between; align-items: center; padding: 24px 28px; background: white; border-bottom: 1px solid var(--line); }
.symbol-dialog-kind { color: var(--muted); font-size: 10px; font-weight: 700; letter-spacing: .1em; }
.symbol-dialog-header h2 { margin: 7px 0 0; font-size: 20px; }
.symbol-dialog-close { border: 0; background: #f3f6fa; width: 30px; height: 30px; border-radius: 6px; font-size: 23px; color: var(--muted); cursor: pointer; }
.symbol-dialog-close:disabled { cursor: wait; opacity: .5; }
.symbol-dialog-body { padding: 24px 28px 0; }
.symbol-dialog-description { margin: 0 0 22px; color: var(--muted-strong); font-size: 12px; line-height: 1.8; }
.symbol-dialog-body .symbol-upload-panel { margin: 0; overflow: visible; }
.symbol-upload-form { padding-top: 0; }
.symbol-upload-fields { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; margin-bottom: 22px; }
.symbol-upload-fields:has(> :only-child) { grid-template-columns: 1fr; }
.symbol-upload-form > .field { margin: 0 0 20px; }
.symbol-upload-form label { font-size: 12px; }
.symbol-required { color: #d36363; }
.symbol-optional { margin-left: 4px; font-size: 10px; font-weight: 400; color: var(--muted); }
.symbol-file-picker { position: relative; border: 1px dashed #b8cce4; background: #f8fbff; border-radius: 8px; padding: 25px 18px; display: flex; flex-direction: column; align-items: center; gap: 9px; text-align: center; }
.symbol-file-picker:hover, .symbol-file-picker:focus-within { border-color: var(--primary); background: #f1f7ff; }
.symbol-file-picker svg { color: #5c8dcc; }
.symbol-file-picker strong { font-size: 13px; font-weight: 500; overflow-wrap: anywhere; max-width: 100%; }
.symbol-file-picker span { font-size: 11px; color: var(--muted); }
.symbol-file-picker input { position: absolute; inset: 0; width: 100%; height: 100%; opacity: 0; cursor: pointer; }
.symbol-upload-hint { font-size: 11px; color: var(--muted); background: #f6f8fb; border-radius: 6px; padding: 12px 14px; line-height: 1.8; margin: 0 0 22px; }
.symbol-dialog-actions { display: flex; justify-content: flex-end; gap: 10px; padding: 18px 0; border-top: 1px solid var(--line); }
.symbol-dialog-actions .button { min-width: 80px; }
.symbol-target { margin: 0 0 20px; padding: 12px; border-left: 3px solid #83aee3; font-size: 12px; background: #f3f8ff; overflow-wrap: anywhere; }
.symbol-target code { display: block; margin-top: 8px; font-size: 11px; }
.symbol-native-conflict { border: 1px solid #ead2a0; background: #fffcf5; padding: 16px; }
.symbol-native-conflict .meta-item { min-width: 0; overflow-wrap: anywhere; font-size: 12px; padding: 12px 0; border-bottom: 1px solid #efe5d0; }
.symbol-native-conflict .meta-item:last-of-type { margin-bottom: 16px; }
.symbol-conflict-panel { border: 1px solid #ead2a0; }
.symbol-conflict-panel .meta-grid { grid-template-columns: 1fr; }
.symbol-conflict-panel .panel-header { padding: 18px; }
.symbol-conflict-panel .meta-grid { padding: 0 18px; }
.detail-card .panel-header > .button { flex-shrink: 0; white-space: nowrap; }
@media (max-width: 1050px) { .symbol-filter-fields { grid-template-columns: repeat(2, minmax(0, 1fr)); } .symbol-filter-note { max-width: 60%; } }
@media (max-width: 680px) { .symbol-filter-fields, .symbol-upload-fields { grid-template-columns: 1fr; } .symbol-filter-note { display: none; } .symbol-filter-actions { justify-content: flex-end; } .symbol-dialog-body { padding: 20px 18px 0; } }
</style>
