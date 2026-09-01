import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { appApi } from '../api/appApi'
import { errorMessage } from '../api/http'
import type { App, AppCreateRequest, AppUpdateRequest } from '../types/app'

export const useAppStore = defineStore('apps', () => {
  const apps = ref<App[]>([])
  const currentApp = ref<App | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  const hasApps = computed(() => apps.value.length > 0)

  async function load(query = '') {
    loading.value = true
    error.value = null
    try {
      apps.value = await appApi.list(query)
      return apps.value
    } catch (requestError) {
      error.value = errorMessage(requestError)
      throw requestError
    } finally {
      loading.value = false
    }
  }

  async function loadOne(appId: string) {
    loading.value = true
    error.value = null
    try {
      currentApp.value = await appApi.get(appId)
      const index = apps.value.findIndex((app) => app.appId === appId)
      if (index >= 0) apps.value[index] = currentApp.value
      return currentApp.value
    } catch (requestError) {
      error.value = errorMessage(requestError)
      throw requestError
    } finally {
      loading.value = false
    }
  }

  async function create(request: AppCreateRequest) {
    const app = await appApi.create(request)
    apps.value = [app, ...apps.value.filter((item) => item.appId !== app.appId)]
    currentApp.value = app
    return app
  }

  async function update(appId: string, request: AppUpdateRequest) {
    const app = await appApi.update(appId, request)
    currentApp.value = app
    const index = apps.value.findIndex((item) => item.appId === appId)
    if (index >= 0) apps.value[index] = app
    return app
  }

  function clearCurrent() {
    currentApp.value = null
  }

  function clear() {
    apps.value = []
    currentApp.value = null
    loading.value = false
    error.value = null
  }

  return { apps, currentApp, loading, error, hasApps, load, loadOne, create, update, clearCurrent, clear }
})
