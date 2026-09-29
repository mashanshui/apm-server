<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { queryTokenApi } from '../api/queryTokenApi'
import { ApiError, errorMessage, isAbortError } from '../api/http'
import type { QueryTokenCreated, QueryTokenMetadata } from '../types/queryToken'

/** 面板只在应用管理员页面挂载，服务端仍独立验证角色。 */
const props = defineProps<{ appId: string; canManage: boolean }>()
/** 列表只存不含完整凭据的管理元数据。 */
const items = ref<QueryTokenMetadata[]>([])
/** 当前零基页码。 */
const page = ref(0)
/** 服务端返回的总页数。 */
const totalPages = ref(0)
/** 列表请求状态。 */
const loading = ref(false)
/** 列表错误。 */
const listError = ref<string | null>(null)
/** 创建表单名称。 */
const name = ref('')
/** 用户选择的合法有效期。 */
const expiresInDays = ref<30 | 90 | 365>(90)
/** 创建请求状态。 */
const creating = ref(false)
/** 创建错误和结果不确定提示。 */
const createError = ref<string | null>(null)
/** 唯一一次完整凭据仅保留在本组件内。 */
const created = ref<QueryTokenCreated | null>(null)
/** 当前完整值的复制结果。 */
const copied = ref(false)
/** 复制失败提示。 */
const copyError = ref<string | null>(null)
/** 等待管理员确认的凭据 ID。 */
const confirmId = ref<string | null>(null)
/** 正在撤销的凭据 ID。 */
const revokingId = ref<string | null>(null)
/** 撤销错误。 */
const revokeError = ref<string | null>(null)
/** 应用切换或认证失败时递增，拦截所有迟到响应。 */
let generation = 0
/** 同一应用内最新列表请求序号。 */
let listSequence = 0
/** 当前所有可取消的网络请求。 */
const controllers = new Set<AbortController>()

/** 清除完整值、取消旧请求并使之前的 Promise 结果失效。 */
function reset() {
  generation += 1
  listSequence += 1
  for (const controller of controllers) controller.abort()
  controllers.clear()
  items.value = []
  page.value = 0
  totalPages.value = 0
  loading.value = false
  listError.value = null
  name.value = ''
  expiresInDays.value = 90
  creating.value = false
  createError.value = null
  created.value = null
  copied.value = false
  copyError.value = null
  confirmId.value = null
  revokingId.value = null
  revokeError.value = null
}

/** 当前应用及角色变化时只读取当前应用的列表。 */
watch(() => [props.appId, props.canManage] as const, () => {
  reset()
  if (props.canManage) void loadList(0)
}, { immediate: true })

/** Session 401 在 HTTP 客户端触发事件时立即清除敏感值。 */
function handleAuthExpired() { reset() }

window.addEventListener('apm:auth-expired', handleAuthExpired)
onBeforeUnmount(() => {
  window.removeEventListener('apm:auth-expired', handleAuthExpired)
  reset()
})

/** 加载指定页，旧页响应不能覆盖新页。 */
async function loadList(targetPage: number) {
  if (!props.canManage) return
  const currentGeneration = generation
  const currentSequence = ++listSequence
  const requestedAppId = props.appId
  const controller = new AbortController()
  controllers.add(controller)
  loading.value = true
  listError.value = null
  try {
    const result = await queryTokenApi.list(requestedAppId, targetPage, 20, controller.signal)
    if (currentGeneration !== generation || currentSequence !== listSequence || requestedAppId !== props.appId) return
    items.value = result.items
    page.value = result.page
    totalPages.value = result.totalPages
  } catch (error) {
    if (currentGeneration !== generation || currentSequence !== listSequence || isAbortError(error)) return
    listError.value = errorMessage(error)
  } finally {
    controllers.delete(controller)
    if (currentGeneration === generation && currentSequence === listSequence) loading.value = false
  }
}

/** 创建操作只发一次；网络断连时提示先刷新列表确认服务端结果。 */
async function create() {
  if (!props.canManage || creating.value) return
  const trimmedName = name.value.trim()
  if (!trimmedName || trimmedName.length > 100) {
    createError.value = '名称长度必须为 1 到 100 字符'
    return
  }
  const currentGeneration = generation
  const requestedAppId = props.appId
  const controller = new AbortController()
  controllers.add(controller)
  creating.value = true
  createError.value = null
  closeCreated()
  try {
    const result = await queryTokenApi.create(requestedAppId,
      { name: trimmedName, expiresInDays: expiresInDays.value }, controller.signal)
    if (currentGeneration !== generation || requestedAppId !== props.appId) return
    created.value = result
    name.value = ''
    void loadList(0)
  } catch (error) {
    if (currentGeneration !== generation || isAbortError(error)) return
    createError.value = error instanceof ApiError && error.status === 0
      ? '创建结果不确定。请先刷新列表，撤销无法找回完整值的 Token，再重新创建。'
      : errorMessage(error)
  } finally {
    controllers.delete(controller)
    if (currentGeneration === generation) creating.value = false
  }
}

/** 主动关闭后无法从列表重新读取完整值。 */
function closeCreated() {
  created.value = null
  copied.value = false
  copyError.value = null
}

/** 复制本次创建结果，失败时保留手动选择方式。 */
async function copyCreated() {
  if (!created.value) return
  const currentGeneration = generation
  const currentToken = created.value.token
  try {
    await navigator.clipboard.writeText(currentToken)
    if (currentGeneration === generation && created.value?.token === currentToken) copied.value = true
  } catch {
    if (currentGeneration === generation) copyError.value = '复制失败，请手动选择 Token'
  }
}

/** 只在二次确认后撤销指定应用的指定 Token。 */
async function revoke(tokenId: string) {
  if (!props.canManage || revokingId.value) return
  const currentGeneration = generation
  const requestedAppId = props.appId
  const controller = new AbortController()
  controllers.add(controller)
  revokingId.value = tokenId
  revokeError.value = null
  try {
    await queryTokenApi.revoke(requestedAppId, tokenId, controller.signal)
    if (currentGeneration !== generation || requestedAppId !== props.appId) return
    confirmId.value = null
    void loadList(page.value)
  } catch (error) {
    if (currentGeneration !== generation || isAbortError(error)) return
    revokeError.value = errorMessage(error)
  } finally {
    controllers.delete(controller)
    if (currentGeneration === generation) revokingId.value = null
  }
}

/** 展示服务端绝对时间，不改变到期判断。 */
function displayTime(value: string) { return new Date(value).toLocaleString('zh-CN') }
</script>

<template>
  <section v-if="canManage" class="panel query-token-panel" aria-label="应用查询 Token">
    <div class="panel-header form-panel-header"><div><h2>应用查询 Token</h2><p>供 MCP 和其他 Agent 查询当前应用；只读，完整值仅创建时展示。</p></div></div>
    <form class="query-token-create" @submit.prevent="create">
      <div class="field"><label for="query-token-name">名称</label><input id="query-token-name" v-model="name" maxlength="100" autocomplete="off" placeholder="例如：值班分析" :disabled="creating"></div>
      <div class="field"><label for="query-token-expiry">有效期</label><select id="query-token-expiry" v-model.number="expiresInDays" :disabled="creating"><option :value="30">30 天</option><option :value="90">90 天</option><option :value="365">365 天</option></select></div>
      <button class="button button-primary" type="submit" :disabled="creating">{{ creating ? '创建中…' : '创建 Token' }}</button>
    </form>
    <p v-if="createError" class="field-error query-token-message" role="alert">{{ createError }}</p>
    <div v-if="created" class="query-token-result" role="status">
      <p>请立即复制并妥善保存。关闭后无法再次查看完整 Token。</p>
      <code class="query-token-secret">{{ created.token }}</code>
      <div class="query-token-actions"><button class="button button-primary" type="button" @click="copyCreated">复制 Token</button><button class="button" type="button" @click="closeCreated">关闭结果</button></div>
      <p v-if="copied" class="notice notice-success">已复制。</p><p v-if="copyError" class="field-error" role="alert">{{ copyError }}</p>
    </div>
    <div class="query-token-list">
      <div class="query-token-list-header"><h3>已创建的 Token</h3><button class="button" type="button" :disabled="loading" @click="loadList(page)">刷新列表</button></div>
      <p v-if="loading">正在加载 Token…</p>
      <p v-if="listError" class="field-error" role="alert">{{ listError }}</p>
      <p v-if="!loading && !listError && items.length === 0">暂无查询 Token。</p>
      <div v-if="items.length" class="table-wrap"><table class="data-table"><thead><tr><th>名称</th><th>前缀</th><th>状态</th><th>到期时间</th><th>操作</th></tr></thead><tbody><tr v-for="item in items" :key="item.id"><td>{{ item.name }}</td><td><code>{{ item.displayPrefix }}…</code></td><td>{{ item.status }}</td><td>{{ displayTime(item.expiresAt) }}</td><td><button v-if="item.status === 'ACTIVE' && confirmId !== item.id" class="button" type="button" @click="confirmId = item.id">撤销</button><span v-else-if="confirmId === item.id" class="query-token-actions"><button class="button" type="button" :disabled="revokingId === item.id" @click="revoke(item.id)">确认撤销</button><button class="button" type="button" :disabled="revokingId === item.id" @click="confirmId = null">取消</button></span></td></tr></tbody></table></div>
      <p v-if="revokeError" class="field-error" role="alert">{{ revokeError }}</p>
      <div v-if="totalPages > 1" class="query-token-pagination"><button class="button" type="button" :disabled="loading || page <= 0" @click="loadList(page - 1)">上一页</button><span>第 {{ page + 1 }} / {{ totalPages }} 页</span><button class="button" type="button" :disabled="loading || page + 1 >= totalPages" @click="loadList(page + 1)">下一页</button></div>
    </div>
  </section>
</template>
