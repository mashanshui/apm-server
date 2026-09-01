import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '../api/authApi'
import { ApiError, errorMessage } from '../api/http'
import type { CurrentUser } from '../types/auth'

export const useSessionStore = defineStore('session', () => {
  const user = ref<CurrentUser | null>(null)
  const initialized = ref(false)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const expired = ref(false)

  const isAuthenticated = computed(() => user.value !== null)

  async function initialize() {
    if (initialized.value || loading.value) return
    loading.value = true
    error.value = null
    try {
      user.value = await authApi.session()
    } catch (requestError) {
      if (requestError instanceof ApiError && requestError.status === 401) {
        user.value = null
      } else {
        error.value = errorMessage(requestError)
      }
    } finally {
      initialized.value = true
      loading.value = false
    }
  }

  async function login(email: string, password: string) {
    loading.value = true
    error.value = null
    expired.value = false
    try {
      user.value = await authApi.login({ email, password })
      initialized.value = true
      return user.value
    } catch (requestError) {
      error.value = errorMessage(requestError)
      throw requestError
    } finally {
      loading.value = false
    }
  }

  async function logout() {
    try {
      await authApi.logout()
    } finally {
      clear()
    }
  }

  function handleExpired() {
    user.value = null
    expired.value = true
    initialized.value = true
  }

  function clear() {
    user.value = null
    error.value = null
    initialized.value = true
  }

  return { user, initialized, loading, error, expired, isAuthenticated, initialize, login, logout, handleExpired, clear }
})
