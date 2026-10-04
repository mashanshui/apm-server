<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { analysisApi } from '../api/analysisApi'
import { ApiError, errorMessage, isAbortError } from '../api/http'
import type { WorkerMetadata } from '../types/analysis'

/** 面板只有管理员可挂载，服务端再次校验角色及 CSRF。 */
const props = defineProps<{ appId: string; canManage: boolean }>()
/** Worker 元数据页，没有完整秘密。 */
const workers = ref<WorkerMetadata[]>([])
/** Worker 列表页码。 */
const workerPage = ref(0)
/** 正在读取列表。 */
const loading = ref(false)
/** 单一管理写入在途。 */
const writing = ref(false)
/** 列表或管理错误。 */
const error = ref<string | null>(null)
/** 创建响应完整值只存运行内存，不能从列表恢复。 */
const secret = ref<string | null>(null)
/** 凭据管理名称。 */
const workerName = ref('')
/** 应用或角色改变使所有在途请求失效。 */
let generation = 0
/** 不同列表请求不能晚到覆盖当前页。 */
let sequence = 0
/** 所有网络请求可中止。 */
const controllers = new Set<AbortController>()

/** 清空完整凭据、字段和旧请求，离开页面也使用此入口。 */
function reset() {
  generation += 1; sequence += 1
  controllers.forEach(controller => controller.abort()); controllers.clear()
  workers.value = []; workerPage.value = 0
  secret.value = null; workerName.value = ''; loading.value = false; writing.value = false; error.value = null
}

/** 身份列表按所属应用有界读取，切换页只展示最新响应。 */
async function load() {
  if (!props.canManage) return
  const current = generation
  const request = ++sequence
  const controller = new AbortController()
  controllers.add(controller); loading.value = true
  try {
    const identities = await analysisApi.workers(props.appId, workerPage.value, controller.signal)
    if (current !== generation || request !== sequence) return
    workers.value = identities
  } catch (failure) {
    if (current === generation && request === sequence && !isAbortError(failure)) error.value = errorMessage(failure)
  } finally { controllers.delete(controller); if (current === generation && request === sequence) loading.value = false }
}

/** 一次完整凭据创建不自动重试，不写浏览器存储或命令。 */
async function createWorker() {
  if (!props.canManage || writing.value || !workerName.value.trim()) return
  const current = generation
  const controller = new AbortController()
  controllers.add(controller); writing.value = true; error.value = null; secret.value = null
  try {
    const result = await analysisApi.createWorker(props.appId, workerName.value.trim(), controller.signal)
    if (current !== generation) return
    secret.value = result.credential; workerName.value = ''; await load()
  } catch (failure) {
    if (current === generation && !isAbortError(failure)) error.value = failure instanceof ApiError && failure.status === 0
      ? '创建结果不确定。请刷新列表，撤销无法找回完整值的凭据后重新创建。' : errorMessage(failure)
  } finally { controllers.delete(controller); if (current === generation) writing.value = false }
}

/** 撤销只作用于当前应用的凭据，运行将停止续租。 */
async function revoke(id: string) {
  if (!props.canManage || writing.value) return
  const current = generation
  const controller = new AbortController()
  controllers.add(controller); writing.value = true; error.value = null; secret.value = null
  try {
    await analysisApi.revokeWorker(props.appId, id, controller.signal)
    if (current === generation) await load()
  } catch (failure) {
    if (current === generation && !isAbortError(failure)) error.value = errorMessage(failure)
  } finally { controllers.delete(controller); if (current === generation) writing.value = false }
}

/** 复制只由用户触发，复制完成后也不保存秘密到浏览器存储。 */
async function copySecret() {
  if (!secret.value) return
  const current = generation
  try { await navigator.clipboard.writeText(secret.value) }
  catch { if (current === generation) error.value = '复制失败，请手动选择完整凭据' }
}

/** 登录失效必须立即清空敏感创建结果。 */
function expired() { reset() }
watch(() => [props.appId, props.canManage] as const, () => { reset(); if (props.canManage) void load() }, { immediate: true })
window.addEventListener('apm:auth-expired', expired)
onBeforeUnmount(() => { window.removeEventListener('apm:auth-expired', expired); reset() })
</script>

<template>
  <section v-if="canManage" class="panel detail-card analysis-admin" aria-label="本地分析配置">
    <h2>本地分析配置</h2><p>为当前应用创建独立 Worker 凭据。源码来自宿主当前项目，模型沿用宿主设置。</p>
    <p v-if="error" class="field-error" role="alert">{{ error }}</p>
    <h3>独立 Worker 凭据</h3><p>仅限定当前应用，30 天有效。查询 Token 和应用上报 Key 不能替代。</p>
    <form class="analysis-worker-form" @submit.prevent="createWorker"><div class="field"><label for="analysis-worker-name">名称</label><input id="analysis-worker-name" v-model="workerName" maxlength="100" required autocomplete="off" :disabled="writing"></div><button class="button button-primary" :disabled="writing">创建 Worker 凭据</button></form>
    <div v-if="secret" class="notice analysis-secret" role="status"><p>完整值仅显示这一次，请立即保存到受保护本地配置。关闭或离开后无法再次读取。</p><code>{{ secret }}</code><div class="analysis-actions"><button class="button" type="button" @click="copySecret">复制凭据</button><button class="button" type="button" @click="secret = null">关闭完整值</button></div></div>
    <div class="table-wrap"><table class="data-table"><thead><tr><th>名称</th><th>前缀</th><th>到期</th><th>状态 / 操作</th></tr></thead><tbody><tr v-for="worker in workers" :key="worker.credentialId"><td>{{ worker.name }}</td><td><code>{{ worker.displayPrefix }}…</code></td><td>{{ new Date(worker.expiresAt).toLocaleString('zh-CN') }}</td><td><span v-if="worker.revokedAt">已撤销</span><span v-else-if="new Date(worker.expiresAt).getTime() <= Date.now()">已到期</span><button v-else class="button" :disabled="writing" @click="revoke(worker.credentialId)">撤销 Worker 凭据</button></td></tr></tbody></table></div>
    <div class="analysis-actions"><button class="button" :disabled="workerPage === 0 || loading" @click="workerPage--; load()">上一凭据页</button><span>第 {{ workerPage + 1 }} 页</span><button class="button" :disabled="workers.length < 20 || workerPage >= 1000 || loading" @click="workerPage++; load()">下一凭据页</button></div>
  </section>
</template>
<style scoped>
.analysis-admin { margin-top: 20px; }
.analysis-worker-form { max-width: 700px; }
.analysis-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin: 14px 0; }
.analysis-secret code { display: block; overflow-wrap: anywhere; }
</style>
