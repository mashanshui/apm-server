<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppLayout from '../components/AppLayout.vue'
import { errorMessage } from '../api/http'
import { useAppStore } from '../stores/apps'
import type { App } from '../types/app'

const router = useRouter()
const apps = useAppStore()
const query = ref('')
const searched = ref(false)
const localError = ref<string | null>(null)

const noApps = computed(() => !apps.loading && !localError.value && apps.apps.length === 0 && !searched.value)
const noResults = computed(() => !apps.loading && !localError.value && apps.apps.length === 0 && searched.value)

onMounted(() => {
  void loadApps()
})

async function loadApps() {
  localError.value = null
  try {
    await apps.load(query.value)
  } catch (error) {
    localError.value = errorMessage(error)
  }
}

async function search() {
  searched.value = Boolean(query.value.trim())
  await loadApps()
}

function roleLabel(role: App['role']) {
  return { OWNER: 'Owner', ADMIN: 'Admin', DEVELOPER: 'Developer', VIEWER: 'Viewer' }[role]
}

function openApp(appId: string) {
  void router.push({ name: 'crash-overview', params: { appId } })
}
</script>

<template>
  <AppLayout>
    <header class="page-header page-header-roomy">
      <div>
        <p class="eyebrow">WORKSPACE / APPS</p>
        <h1>我的应用</h1>
        <p class="subtitle">管理应用基础信息，进入对应工作区查看移动应用健康状况。</p>
      </div>
      <RouterLink class="button button-primary" :to="{ name: 'app-create' }">＋ 创建应用</RouterLink>
    </header>

    <section class="panel app-toolbar-panel">
      <form class="app-search" @submit.prevent="search">
        <div class="field search-field">
          <label for="app-search">搜索应用</label>
          <div class="search-input-wrap">
            <span class="search-icon" aria-hidden="true">⌕</span>
            <input id="app-search" v-model="query" type="search" placeholder="按应用名称或标识搜索">
          </div>
        </div>
        <button class="button" type="submit" :disabled="apps.loading">搜索</button>
        <button v-if="searched" class="button button-quiet" type="button" @click="query = ''; searched = false; void loadApps()">清除</button>
      </form>
      <span class="toolbar-count">{{ apps.apps.length }} 个应用</span>
    </section>

    <div v-if="localError" class="panel error-state page-state">
      <div>
        <p>{{ localError }}</p>
        <button class="button" type="button" @click="loadApps">重试</button>
      </div>
    </div>

    <section v-else-if="apps.loading" class="panel loading-state page-state"><p>正在加载应用…</p></section>

    <section v-else-if="noApps" class="panel empty-app-state">
      <div class="empty-app-icon">✦</div>
      <p class="eyebrow">FIRST APP</p>
      <h2>创建你的第一个应用</h2>
      <p>应用是 Crash 数据、成员权限和分析工作区的边界。</p>
      <RouterLink class="button button-primary" :to="{ name: 'app-create' }">开始创建应用</RouterLink>
    </section>

    <section v-else-if="noResults" class="panel empty-app-state compact-empty">
      <div class="empty-app-icon">⌕</div>
      <h2>没有匹配的应用</h2>
      <p>换一个名称或应用标识试试。</p>
    </section>

    <section v-else class="panel app-table-panel">
      <table class="data-table app-table">
        <thead>
          <tr><th>应用</th><th>包名</th><th>我的角色</th><th>最近更新</th><th><span class="sr-only">操作</span></th></tr>
        </thead>
        <tbody>
          <tr v-for="app in apps.apps" :key="app.appId">
            <td>
              <button class="app-name-button" type="button" @click="openApp(app.appId)">
                <span class="app-avatar">{{ app.name.slice(0, 1) }}</span>
                <span><strong>{{ app.name }}</strong><small>{{ app.description || '暂无描述' }}</small></span>
              </button>
            </td>
            <td><code class="app-id">{{ app.packageName }}</code></td>
            <td><span class="role-badge" :class="`role-${app.role.toLowerCase()}`">{{ roleLabel(app.role) }}</span></td>
            <td class="muted-cell">{{ new Date(app.updatedAt).toLocaleDateString('zh-CN') }}</td>
            <td class="row-actions">
              <button class="link-button" type="button" @click="openApp(app.appId)">进入分析</button>
              <RouterLink class="link-button" :to="{ name: 'app-settings', params: { appId: app.appId } }">设置</RouterLink>
            </td>
          </tr>
        </tbody>
      </table>
    </section>
  </AppLayout>
</template>
