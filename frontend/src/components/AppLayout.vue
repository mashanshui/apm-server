<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAppStore } from '../stores/apps'
import { useSessionStore } from '../stores/session'
import { jankAppSwitchTarget } from '../utils/jankNavigation'

const props = defineProps<{ appId?: string }>()
const route = useRoute()
const router = useRouter()
const apps = useAppStore()
const session = useSessionStore()

const currentApp = computed(() => apps.apps.find((app) => app.appId === props.appId))
const crashActive = computed(() => String(route.name ?? '').startsWith('crash-'))
const jankMetricsActive = computed(() => route.name === 'jank-metrics')
const jankIssuesActive = computed(() => ['jank-issues', 'jank-issue-events', 'jank-event-detail'].includes(String(route.name ?? '')))
const settingsActive = computed(() => route.name === 'app-settings')

onMounted(() => {
  if (session.isAuthenticated && apps.apps.length === 0) {
    void apps.load()
  }
})

function switchApp(event: Event) {
  const nextAppId = (event.target as HTMLSelectElement).value
  if (!nextAppId || nextAppId === props.appId) return
  apps.clearCurrent()
  const jankTarget = jankAppSwitchTarget(route.name, nextAppId, route.query)
  void router.push(jankTarget ?? { name: 'crash-overview', params: { appId: nextAppId } })
}

async function logout() {
  try {
    await session.logout()
  } finally {
    apps.clear()
    await router.push({ name: 'login' })
  }
}
</script>

<template>
  <div class="app-shell">
    <aside class="app-sidebar">
      <RouterLink class="brand" :to="{ name: 'apps' }">
        <div class="brand-mark">A</div>
        <div class="brand-copy">
          <strong>Android APM</strong>
          <span>可观测性控制台</span>
        </div>
      </RouterLink>

      <nav aria-label="主导航" class="sidebar-nav">
        <p class="nav-group-label">工作区</p>
        <RouterLink class="nav-link" :to="{ name: 'apps' }">
          <span class="nav-icon">▦</span>
          <span>我的应用</span>
        </RouterLink>
        <RouterLink class="nav-link" :to="{ name: 'app-create' }">
          <span class="nav-icon">＋</span>
          <span>创建应用</span>
        </RouterLink>

        <template v-if="props.appId">
          <p class="nav-group-label nav-group-spaced">应用分析</p>
          <RouterLink
            class="nav-link"
            :class="{ 'router-link-active': crashActive }"
            :to="{ name: 'crash-overview', params: { appId: props.appId } }"
          >
            <span class="nav-icon">⌁</span>
            <span>JVM Crash</span>
          </RouterLink>
          <RouterLink
            class="nav-link"
            :class="{ 'router-link-active': jankMetricsActive }"
            :to="{ name: 'jank-metrics', params: { appId: props.appId } }"
          >
            <span class="nav-icon">⌁</span>
            <span>卡顿指标分析</span>
          </RouterLink>
          <RouterLink
            class="nav-link"
            :class="{ 'router-link-active': jankIssuesActive }"
            :to="{ name: 'jank-issues', params: { appId: props.appId } }"
          >
            <span class="nav-icon">≋</span>
            <span>卡顿问题分析</span>
          </RouterLink>
          <RouterLink
            class="nav-link"
            :class="{ 'router-link-active': settingsActive }"
            :to="{ name: 'app-settings', params: { appId: props.appId } }"
          >
            <span class="nav-icon">⚙</span>
            <span>应用设置</span>
          </RouterLink>
        </template>
      </nav>

      <div class="sidebar-footer">
        <span class="status-dot" aria-hidden="true"></span>
        <span>服务运行正常</span>
      </div>
    </aside>

    <main class="app-main">
      <header class="app-topbar">
        <div class="topbar-context">
          <span class="topbar-kicker">ANDROID APM</span>
          <span class="topbar-separator">/</span>
          <span>{{ props.appId ? (currentApp?.name || props.appId) : '应用工作区' }}</span>
        </div>
        <div class="topbar-actions">
          <label v-if="props.appId" class="app-switcher">
            <span class="sr-only">切换应用</span>
            <select :value="props.appId" @change="switchApp">
              <option v-if="!currentApp" :value="props.appId">{{ props.appId }}</option>
              <option v-for="app in apps.apps" :key="app.appId" :value="app.appId">
                {{ app.name }}
              </option>
            </select>
          </label>
          <details class="user-menu">
            <summary>
              <span class="user-avatar">{{ (session.user?.displayName || 'U').slice(0, 1).toUpperCase() }}</span>
              <span class="user-name">{{ session.user?.displayName || '当前用户' }}</span>
              <span class="chevron">⌄</span>
            </summary>
            <div class="user-menu-panel">
              <p>{{ session.user?.email }}</p>
              <button class="menu-button" type="button" @click="logout">退出登录</button>
            </div>
          </details>
        </div>
      </header>

      <div class="page-container">
        <slot />
      </div>
    </main>
  </div>
</template>
