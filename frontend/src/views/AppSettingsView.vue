<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import AppLayout from '../components/AppLayout.vue'
import { ApiError, errorMessage } from '../api/http'
import { useAppStore } from '../stores/apps'
import { appApi } from '../api/appApi'
import AnalysisAdminPanel from '../components/AnalysisAdminPanel.vue'
import QueryTokenPanel from '../components/QueryTokenPanel.vue'

const route = useRoute()
const router = useRouter()
const apps = useAppStore()
const name = ref('')
const description = ref('')
const originalName = ref('')
const originalDescription = ref('')
const loading = ref(true)
const saving = ref(false)
const error = ref<string | null>(null)
const saved = ref(false)
const fieldError = ref<string | null>(null)
const appKey = ref<string | null>(null)
const keyVisible = ref(false)
const credentialLoading = ref(false)
const credentialError = ref<string | null>(null)
const copied = ref(false)
let credentialRequest = 0
let credentialAbortController: AbortController | null = null

const appId = computed(() => String(route.params.appId))
const app = computed(() => apps.currentApp)
const canEdit = computed(() => app.value?.role === 'OWNER' || app.value?.role === 'ADMIN')
const canViewCredential = computed(() => canEdit.value)
const dirty = computed(() => name.value !== originalName.value || description.value !== originalDescription.value)

async function loadApp(expectedAppId: string) {
  loading.value = true
  error.value = null
  try {
    const loaded = await apps.loadOne(expectedAppId)
    if (expectedAppId !== appId.value) return
    name.value = loaded.name
    description.value = loaded.description || ''
    originalName.value = name.value
    originalDescription.value = description.value
  } catch (requestError) {
    error.value = errorMessage(requestError)
  } finally {
    if (expectedAppId === appId.value) loading.value = false
  }
}

watch(appId, (value) => {
  clearCredential()
  void loadApp(value)
}, { immediate: true })

function beforeUnload(event: BeforeUnloadEvent) {
  if (dirty.value) {
    event.preventDefault()
    event.returnValue = ''
  }
}

watch(dirty, (value) => {
  if (value) window.addEventListener('beforeunload', beforeUnload)
  else window.removeEventListener('beforeunload', beforeUnload)
})
function clearCredential() {
  credentialRequest += 1
  credentialAbortController?.abort()
  credentialAbortController = null
  appKey.value = null
  keyVisible.value = false
  credentialLoading.value = false
  credentialError.value = null
  copied.value = false
}

function handleAuthExpired() {
  clearCredential()
}

window.addEventListener('apm:auth-expired', handleAuthExpired)
onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', beforeUnload)
  window.removeEventListener('apm:auth-expired', handleAuthExpired)
  clearCredential()
})

onBeforeRouteLeave(() => {
  if (dirty.value && !window.confirm('当前页面有未保存的修改，确定离开吗？')) return false
  clearCredential()
  return true
})

async function toggleCredential() {
  copied.value = false
  credentialError.value = null
  if (appKey.value) {
    keyVisible.value = !keyVisible.value
    return
  }
  if (!canViewCredential.value || credentialLoading.value) return
  const requestId = ++credentialRequest
  const requestedAppId = appId.value
  credentialAbortController?.abort()
  const controller = new AbortController()
  credentialAbortController = controller
  credentialLoading.value = true
  try {
    const credential = await appApi.getIngestCredential(requestedAppId, controller.signal)
    if (requestId !== credentialRequest || requestedAppId !== appId.value) return
    appKey.value = credential.appKey
    keyVisible.value = true
  } catch (requestError) {
    if (requestId !== credentialRequest) return
    appKey.value = null
    keyVisible.value = false
    credentialError.value = errorMessage(requestError)
  } finally {
    if (requestId === credentialRequest) {
      credentialLoading.value = false
      credentialAbortController = null
    }
  }
}

async function copyCredential() {
  if (!appKey.value || !keyVisible.value) return
  try {
    await navigator.clipboard.writeText(appKey.value)
    copied.value = true
  } catch {
    copied.value = false
    credentialError.value = '复制失败，请手动选择 Key'
  }
}

async function save() {
  if (!canEdit.value || saving.value) {
    return
  }
  if (!name.value.trim()) {
    fieldError.value = '请输入应用名称'
    return
  }
  saving.value = true
  error.value = null
  fieldError.value = null
  saved.value = false
  try {
    const updated = await apps.update(appId.value, { name: name.value.trim(), description: description.value.trim() })
    name.value = updated.name
    description.value = updated.description || ''
    originalName.value = name.value
    originalDescription.value = description.value
    saved.value = true
  } catch (requestError) {
    error.value = requestError instanceof ApiError ? requestError.message : errorMessage(requestError)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <AppLayout :app-id="appId">
    <div class="breadcrumb"><RouterLink :to="{ name: 'apps' }">我的应用</RouterLink><span class="breadcrumb-separator">/</span><span>{{ app?.name || appId }}</span><span class="breadcrumb-separator">/</span><span>应用设置</span></div>
    <header class="page-header page-header-roomy"><div><p class="eyebrow">APP / SETTINGS</p><h1>应用设置</h1><p class="subtitle">维护应用的基础信息与当前访问角色。</p></div><RouterLink class="button button-primary" :to="{ name: 'crash-overview', params: { appId } }">进入 JVM Crash</RouterLink></header>

    <div v-if="loading" class="panel loading-state page-state"><p>正在加载应用设置…</p></div>
    <div v-else-if="error && !app" class="panel error-state page-state"><div><p>{{ error }}</p><button class="button" type="button" @click="router.go(0)">重试</button></div></div>
    <template v-else-if="app">
      <div v-if="route.query.created === '1'" class="notice notice-success" role="status">应用已创建。你现在是该应用的 Owner。</div>
      <div v-if="saved" class="notice notice-success" role="status">应用设置已保存。</div>
      <div v-if="error" class="form-alert" role="alert">{{ error }}</div>
      <div class="settings-layout">
        <form class="panel settings-form" @submit.prevent="save">
          <div class="panel-header form-panel-header"><div><h2>基本信息</h2><p>这些信息会显示在应用工作区中。</p></div><span class="role-badge" :class="`role-${app.role.toLowerCase()}`">{{ app.role }}</span></div>
          <div class="settings-form-body"><div class="field"><label for="settings-name">应用名称</label><input id="settings-name" v-model="name" maxlength="100" :disabled="!canEdit"><p v-if="fieldError" class="field-error">{{ fieldError }}</p></div><div class="field"><label for="settings-description">应用描述</label><textarea id="settings-description" v-model="description" maxlength="500" rows="5" :disabled="!canEdit"></textarea></div><div class="form-actions"><span v-if="!canEdit" class="readonly-note">当前角色只能查看应用设置</span><span v-else-if="dirty" class="unsaved-note">有未保存修改</span><button v-if="canEdit" class="button button-primary" type="submit" :disabled="saving || !dirty">{{ saving ? '保存中…' : '保存修改' }}</button></div></div>
        </form>
        <aside class="panel settings-meta"><div class="panel-header form-panel-header"><div><h2>应用详情</h2><p>只读身份信息</p></div></div><dl class="settings-meta-list"><div><dt>应用标识</dt><dd><code>{{ app.appId }}</code></dd></div><div><dt>应用包名</dt><dd><code>{{ app.packageName }}</code></dd></div><div><dt>当前角色</dt><dd>{{ app.role }}</dd></div><div><dt>创建时间</dt><dd>{{ new Date(app.createdAt).toLocaleString('zh-CN') }}</dd></div><div><dt>最近更新</dt><dd>{{ new Date(app.updatedAt).toLocaleString('zh-CN') }}</dd></div></dl><div v-if="canViewCredential" class="credential-panel"><h3>应用上报 Key</h3><p class="readonly-note">此 Key 永久有效并与应用包名绑定，请勿写入日志或提交到仓库。</p><div v-if="appKey" class="credential-value"><code>{{ keyVisible ? appKey : '••••••••••••••••••••••••' }}</code></div><div class="form-actions"><button class="button" type="button" :disabled="credentialLoading" @click="toggleCredential">{{ credentialLoading ? '正在读取…' : appKey && keyVisible ? '隐藏 Key' : '查看 Key' }}</button><button v-if="appKey && keyVisible" class="button button-primary" type="button" @click="copyCredential">复制 Key</button></div><p v-if="copied" class="notice notice-success" role="status">Key 已复制。</p><p v-if="credentialError" class="field-error" role="alert">{{ credentialError }}</p></div></aside>
      </div>
      <AnalysisAdminPanel v-if="canEdit && app.appId === appId" :app-id="appId" :can-manage="canEdit" />
      <QueryTokenPanel v-if="canEdit" :app-id="appId" :can-manage="canEdit" />
    </template>
  </AppLayout>
</template>
