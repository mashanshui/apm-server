<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError } from '../api/http'
import { useSessionStore } from '../stores/session'
import { safeRedirect } from '../utils/navigation'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const email = ref('')
const password = ref('')
const localError = ref<string | null>(null)
const submitted = ref(false)

const expired = computed(() => route.query.expired === '1')
const errorMessage = computed(() => localError.value || session.error)

async function submit() {
  submitted.value = true
  localError.value = null
  if (!email.value.trim() || !password.value) {
    localError.value = '请输入邮箱和密码'
    return
  }
  try {
    await session.login(email.value, password.value)
    await router.push(safeRedirect(route.query.redirect))
  } catch (error) {
    if (!(error instanceof ApiError)) {
      localError.value = '登录失败，请稍后重试'
    }
  } finally {
    submitted.value = false
  }
}
</script>

<template>
  <main class="auth-shell">
    <section class="auth-intro">
      <RouterLink class="brand auth-brand" :to="{ name: 'login' }">
        <span class="brand-mark">A</span>
        <span class="brand-copy">
          <strong>Android APM</strong>
          <span>可观测性控制台</span>
        </span>
      </RouterLink>
      <div class="auth-intro-copy">
        <p class="eyebrow">OBSERVE WITH CONFIDENCE</p>
        <h1>让每一次崩溃，都能找到答案。</h1>
        <p>从应用趋势到原始堆栈，在一个清晰、可靠的工作区里完成移动应用排障。</p>
      </div>
      <div class="auth-points" aria-label="平台能力">
        <span><i>01</i> JVM Crash 趋势与问题归组</span>
        <span><i>02</i> 脱敏堆栈与事件下钻</span>
        <span><i>03</i> 应用级权限与安全会话</span>
      </div>
    </section>

    <section class="auth-card-wrap">
      <div class="auth-card panel">
        <div class="auth-card-heading">
          <p class="eyebrow">WELCOME BACK</p>
          <h2>登录控制台</h2>
          <p>使用你的工作邮箱继续。</p>
        </div>

        <div v-if="expired" class="notice notice-info" role="status">登录状态已过期，请重新登录。</div>
        <div v-if="errorMessage" class="form-alert" role="alert">{{ errorMessage }}</div>

        <form class="auth-form" @submit.prevent="submit">
          <div class="field">
            <label for="email">工作邮箱</label>
            <input
              id="email"
              v-model="email"
              autocomplete="username"
              type="email"
              placeholder="you@company.com"
              :disabled="submitted"
              required
            >
          </div>
          <div class="field">
            <div class="field-label-row">
              <label for="password">密码</label>
            </div>
            <input
              id="password"
              v-model="password"
              autocomplete="current-password"
              type="password"
              placeholder="请输入密码"
              :disabled="submitted"
              required
            >
          </div>
          <button class="button button-primary button-wide" type="submit" :disabled="submitted">
            <span v-if="submitted" class="button-spinner" aria-hidden="true"></span>
            {{ submitted ? '正在登录…' : '登录' }}
          </button>
        </form>
        <p class="auth-footnote">需要访问权限？请联系你的平台管理员。</p>
      </div>
    </section>
  </main>
</template>
