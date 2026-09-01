<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import AppLayout from '../components/AppLayout.vue'
import { ApiError, errorMessage } from '../api/http'
import { useAppStore } from '../stores/apps'
import type { AppCreateRequest } from '../types/app'

const router = useRouter()
const apps = useAppStore()
const name = ref('')
const description = ref('')
const packageName = ref('')
const submitting = ref(false)
const error = ref<string | null>(null)
const nameError = ref<string | null>(null)
const descriptionError = ref<string | null>(null)
const packageNameError = ref<string | null>(null)

function validate(): boolean {
  error.value = null
  nameError.value = null
  descriptionError.value = null
  packageNameError.value = null
  const trimmedName = name.value.trim()
  const trimmedDescription = description.value.trim()
  const value = packageName.value.trim()
  if (trimmedName.length > 100) {
    nameError.value = '应用名称不能超过 100 个字符'
  }
  if (trimmedDescription.length > 500) {
    descriptionError.value = '应用描述不能超过 500 个字符'
  }
  if (value.length > 255
    || !/^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/.test(value)) {
    packageNameError.value = '请输入全小写且唯一的 Android 包名，例如 com.example.app'
  }
  return !nameError.value && !descriptionError.value && !packageNameError.value
}

async function submit() {
  if (submitting.value || !validate()) return
  submitting.value = true
  try {
    const request: AppCreateRequest = { packageName: packageName.value.trim() }
    if (name.value.trim()) request.name = name.value.trim()
    if (description.value.trim()) request.description = description.value.trim()
    const app = await apps.create(request)
    await router.push({ name: 'app-settings', params: { appId: app.appId }, query: { created: '1' } })
  } catch (requestError) {
    if (requestError instanceof ApiError && requestError.code === 'PACKAGE_NAME_CONFLICT') {
      packageNameError.value = '这个包名已经被使用，请换一个。'
    } else {
      error.value = errorMessage(requestError)
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AppLayout>
    <div class="breadcrumb"><RouterLink :to="{ name: 'apps' }">我的应用</RouterLink><span class="breadcrumb-separator">/</span><span>创建应用</span></div>
    <header class="page-header page-header-roomy">
      <div><p class="eyebrow">WORKSPACE / NEW APP</p><h1>创建应用</h1><p class="subtitle">为一个 Android 应用建立独立的 Crash 与卡顿分析边界。</p></div>
    </header>

    <div class="form-layout">
      <form class="panel app-form" @submit.prevent="submit">
        <div class="panel-header form-panel-header"><div><h2>应用信息</h2><p>服务端会自动生成 UUID v4 格式的 appId；名称和描述可稍后在设置中修改。</p></div><span class="step-label">01 / 01</span></div>
        <div v-if="error" class="form-alert" role="alert">{{ error }}</div>
        <div class="field"><label for="app-name">应用名称</label><input id="app-name" v-model="name" maxlength="100" placeholder="例如：测试 Demo" autocomplete="off"><small>可选；留空时使用 Android 包名作为显示名称。</small><p v-if="nameError" class="field-error" role="alert">{{ nameError }}</p></div>
        <div class="field"><label for="app-description">应用描述</label><textarea id="app-description" v-model="description" maxlength="500" rows="4" placeholder="简要说明这个应用或环境" autocomplete="off"></textarea><small>可选，最多 500 个字符。</small><p v-if="descriptionError" class="field-error" role="alert">{{ descriptionError }}</p></div>
        <div class="field"><label for="app-package-name">Android 包名 <span class="required-mark">*</span></label><input id="app-package-name" v-model="packageName" maxlength="255" placeholder="com.example.app" required autocomplete="off"><small>只能使用全小写字母、数字、下划线和点，至少包含两段；创建后不可修改并与 appKey 永久绑定。</small><p v-if="packageNameError" class="field-error" role="alert">{{ packageNameError }}</p></div>
        <div class="form-actions"><RouterLink class="button" :to="{ name: 'apps' }">取消</RouterLink><button class="button button-primary" type="submit" :disabled="submitting">{{ submitting ? '正在创建…' : '创建应用' }}</button></div>
      </form>

      <aside class="panel form-aside"><p class="eyebrow">APP WORKSPACE</p><h2>创建后你将拥有</h2><ul class="feature-list"><li><span>✓</span><div><strong>系统生成 appId</strong><p>每个应用拥有公开、不可变的 UUID v4 标识。</p></div></li><li><span>✓</span><div><strong>永久 appKey</strong><p>Key 与包名绑定，可在应用设置中随时查看。</p></div></li><li><span>✓</span><div><strong>独立分析边界</strong><p>Crash、卡顿数据和成员权限按应用隔离。</p></div></li></ul><div class="preview-card"><span class="preview-label">APP NAME</span><strong>{{ name.trim() || packageName.trim() || '我的应用' }}</strong><span class="preview-label">PACKAGE NAME</span><code>{{ packageName.trim() || 'com.example.app' }}</code></div></aside>
    </div>
  </AppLayout>
</template>
