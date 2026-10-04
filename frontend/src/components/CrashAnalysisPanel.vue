<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { analysisApi } from '../api/analysisApi'
import { ApiError, errorMessage, isAbortError } from '../api/http'
import type { AnalysisRunDetail, AnalysisTask } from '../types/analysis'

/** 面板只解释传入的单次事件，角色与当前应用已由父页面核对。 */
const props = defineProps<{ appId: string; eventId: string; canCreate: boolean; canManage: boolean; userId: string | null }>()
/** 当前事件任务页。 */
const tasks = ref<AnalysisTask[]>([])
/** 任务页码。 */
const page = ref(0)
/** 选中的分析任务 ID。 */
const selectedId = ref<string | null>(null)
/** 当前任务每次尝试。 */
const runs = ref<AnalysisRunDetail[]>([])
/** 执行历史页码。 */
const runPage = ref(0)
/** 当前列表及写入状态。 */
const loading = ref(false)
/** 执行历史在途时不提前开放重试。 */
const runsLoading = ref(false)
/** 同时最多一个写操作。 */
const writing = ref(false)
/** 面向人的错误，不含 Worker 密钥。 */
const error = ref<string | null>(null)
/** 创建响应未知时复用相同幂等键。 */
let createKey: string | null = null
/** 应用或事件切换使旧读写失效。 */
let generation = 0
/** 当前列表请求代次。 */
let listSequence = 0
/** 当前执行历史请求代次。 */
let runSequence = 0
/** 所有当前请求可随切换取消。 */
const controllers = new Set<AbortController>()
/** 活动任务五秒轮询，终态停止。 */
let timer: ReturnType<typeof setTimeout> | null = null
/** 管理员实际环境核验依据。 */
const stopBasis = ref('')
/** 当前选中任务以最新元数据展示。 */
const selected = computed(() => tasks.value.find(task => task.taskId === selectedId.value) ?? null)
/** 未包含任何凭据的手动命令，不宣称网页已启动本地进程。 */
const command = computed(() => selected.value ? `使用 apm-crash-analyze 分析任务 ${selected.value.taskId}` : '')
/** 用户明确要求修复的示例，不从网页自动启动宿主。 */
const repairCommand = computed(() => selected.value ? `使用 apm-crash-analyze 分析并修复任务 ${selected.value.taskId}，使用当前项目代码` : '')
/** 修改、保存报告与测试结果分别展示。 */
const repairStates: Record<string, string> = { NOT_REQUESTED: '未请求修复', NOT_APPLICABLE: '未执行修改', APPLIED: '已修改代码', PARTIAL: '部分修改完成', CONFLICT: '工作区冲突', FAILED: '修改失败或已撤回' }
/** 自报测试结果不能冒充平台独立验证。 */
const verificationStates: Record<string, string> = { PASSED: '宿主验证通过', FAILED: '验证失败', NOT_RUN: '尚未验证' }
/** 任务状态中文展示。 */
const states: Record<string, string> = { BLOCKED: '证据准备未完成', READY: '等待本地手动执行', RUNNING: '分析中', CANCELLING: '等待停止确认', SUCCEEDED: '报告已保存', FAILED: '执行失败', CANCELLED: '已取消' }
/** 前提与稳定执行错误的说明。 */
const reasons: Record<string, string> = {
  EVIDENCE_PREPARING: '正在准备单事件证据', SYMBOL_VERSION_CHANGED: '准备期间 mapping 变化，请重新检查',
  EVIDENCE_TOO_LARGE: '完整证据超过上限，未截断或调用模型', PREPARATION_FAILED: '证据准备失败，请检查后重新检查',
  EVENT_IDENTITY_CHANGED: '事件输入发生变化，请管理员核对', LEASE_EXPIRED: '租约到期，环境停止仍须确认',
  TIME_LIMIT: '达到运行时间上限', CONFIG_MISMATCH: '任务执行策略不匹配', SOURCE_MISSING: '本地项目或源码材料不可用',
  EXECUTOR_FAILED: '执行器失败', INPUT_LIMIT: '达到材料交付上限', REQUEST_LIMIT: '达到模型请求上限',
  FORMAT_INVALID: '模型回答未通过结构校验', REFERENCE_INVALID: '模型引用未通过核验', NETWORK_ERROR: '平台网络请求失败',
  WORKER_CREDENTIAL_INVALID: 'Worker 凭据已撤销或到期，停止尚未确认', CANCELLED: '已取消执行',
}

/** 清空当前事件数据并拦截迟到响应，不在浏览器保存秘密。 */
function reset() {
  generation += 1
  listSequence += 1
  runSequence += 1
  controllers.forEach(controller => controller.abort())
  controllers.clear()
  if (timer) clearTimeout(timer)
  timer = null
  tasks.value = []; runs.value = []; page.value = 0; runPage.value = 0; selectedId.value = null
  loading.value = false; runsLoading.value = false; writing.value = false; error.value = null; createKey = null; stopBasis.value = ''
}

/** 有活动任务或停止未知时继续对账，结果过期不会伪装为空成功。 */
function schedule() {
  if (timer) clearTimeout(timer)
  const active = tasks.value.some(task => ['READY', 'RUNNING', 'CANCELLING'].includes(task.state) || task.blockReason === 'EVIDENCE_PREPARING')
  if (active || runs.value.some(item => !item.run.stopConfirmed)) timer = setTimeout(() => { void load(page.value) }, 5000)
}

/** 获取有界事件历史，列表响应代次防止上一页覆盖当前页。 */
async function load(targetPage: number) {
  const current = generation
  const sequence = ++listSequence
  const controller = new AbortController()
  controllers.add(controller)
  loading.value = true
  try {
    const result = await analysisApi.list(props.appId, props.eventId, targetPage, controller.signal)
    if (current !== generation || sequence !== listSequence) return
    tasks.value = result; page.value = targetPage
    if (!result.some(task => task.taskId === selectedId.value)) { selectedId.value = result[0]?.taskId ?? null; runPage.value = 0 }
    await loadRuns(runPage.value)
  } catch (failure) {
    if (current === generation && sequence === listSequence && !isAbortError(failure)) error.value = errorMessage(failure)
  } finally {
    controllers.delete(controller)
    if (current === generation && sequence === listSequence) { loading.value = false; schedule() }
  }
}

/** 指定任务执行历史，快速切换任务不会混入旧结果。 */
async function loadRuns(targetPage: number) {
  const current = generation
  const sequence = ++runSequence
  const id = selectedId.value
  if (!id) { runs.value = []; return }
  const controller = new AbortController()
  controllers.add(controller)
  runsLoading.value = true
  try {
    const result = await analysisApi.runs(props.appId, id, targetPage, controller.signal)
    if (current !== generation || sequence !== runSequence || id !== selectedId.value) return
    runs.value = result; runPage.value = targetPage
  } catch (failure) {
    if (current === generation && sequence === runSequence && !isAbortError(failure)) error.value = errorMessage(failure)
  } finally { controllers.delete(controller); if (current === generation && sequence === runSequence) runsLoading.value = false }
}

/** 用户主动选择另一任务时清除旧结果及旧分页。 */
function select(id: string) { selectedId.value = id; runs.value = []; runPage.value = 0; stopBasis.value = ''; void loadRuns(0) }

/** 单次创建不自动重试，用户重试时沿用稳定 UUID。 */
async function create() {
  if (!props.canCreate || writing.value) return
  const current = generation
  const controller = new AbortController()
  controllers.add(controller); writing.value = true; error.value = null
  createKey ??= crypto.randomUUID()
  try {
    const result = await analysisApi.create(props.appId, props.eventId, createKey, controller.signal)
    if (current !== generation) return
    createKey = null; selectedId.value = result.taskId; runPage.value = 0
    await load(0)
  } catch (failure) {
    if (current === generation && !isAbortError(failure)) error.value = failure instanceof ApiError && failure.status === 0
      ? '创建响应不确定。请刷新历史或使用同一按钮重试，将复用原幂等键。' : errorMessage(failure)
  } finally { controllers.delete(controller); if (current === generation) writing.value = false }
}

/** 创建者或管理员取消；重检和重试要求创建权限，后端仍作最终裁决。 */
async function action(value: 'recheck' | 'retry' | 'cancel') {
  const task = selected.value
  if (!task || writing.value || !props.canCreate
      || (value === 'cancel' && !props.canManage && task.createdBy !== props.userId)) return
  const current = generation
  const controller = new AbortController()
  controllers.add(controller); writing.value = true; error.value = null
  try {
    await analysisApi.action(props.appId, task.taskId, value, controller.signal)
    if (current === generation) await load(page.value)
  } catch (failure) {
    if (current === generation && !isAbortError(failure)) error.value = errorMessage(failure)
  } finally { controllers.delete(controller); if (current === generation) writing.value = false }
}

/** 管理员先实际核验对应环境停止，再留下审计，模型回答不能代替核验。 */
async function confirmStop(runId: string) {
  if (!props.canManage || !stopBasis.value.trim() || writing.value) return
  const current = generation
  const controller = new AbortController()
  controllers.add(controller); writing.value = true; error.value = null
  try {
    await analysisApi.confirmStopped(props.appId, runId, stopBasis.value.trim(), controller.signal)
    if (current === generation) { stopBasis.value = ''; await load(page.value) }
  } catch (failure) {
    if (current === generation && !isAbortError(failure)) error.value = errorMessage(failure)
  } finally { controllers.delete(controller); if (current === generation) writing.value = false }
}

watch(() => [props.appId, props.eventId] as const, () => { reset(); void load(0) }, { immediate: true })
/** 会话到期时终止轮询并清空旧应用分析数据。 */
function expired() { reset() }
window.addEventListener('apm:auth-expired', expired)
onBeforeUnmount(() => { window.removeEventListener('apm:auth-expired', expired); reset() })
</script>

<template>
  <section class="panel detail-card analysis-panel" aria-label="单事件本地分析">
    <div class="panel-header"><div><h2>单事件本地分析</h2><p>针对当前 eventId 和宿主当前项目代码分析。当前工作区可包含未提交修改，与历史 APK 可能不同；明确要求修复后才修改本地源码，网页创建不会启动本地进程。</p></div>
      <button v-if="canCreate" class="button button-primary" data-testid="create-analysis" :disabled="writing" @click="create">{{ writing ? '处理中…' : '创建分析任务' }}</button>
    </div>
    <p v-if="error" class="field-error" role="alert">{{ error }}</p>
    <p v-if="!loading && !tasks.length">暂无此事件的分析任务。</p>
    <div class="analysis-actions"><button class="button" :disabled="loading || writing" @click="load(page)">刷新历史</button><button class="button" :disabled="page === 0 || loading" @click="load(page - 1)">上一页</button><span>任务第 {{ page + 1 }} 页</span><button class="button" :disabled="tasks.length < 20 || page >= 1000 || loading" @click="load(page + 1)">下一页</button></div>
    <div class="analysis-task-list"><button v-for="task in tasks" :key="task.taskId" class="button" :class="{ 'button-primary': task.taskId === selectedId }" @click="select(task.taskId)">{{ states[task.state] }} · {{ new Date(task.createdAt).toLocaleString('zh-CN') }}</button></div>
    <template v-if="selected">
      <dl class="meta-grid analysis-meta"><div class="meta-item"><dt>状态</dt><dd>{{ states[selected.state] }}</dd></div><div class="meta-item"><dt>分析任务</dt><dd><code>{{ selected.taskId }}</code></dd></div><div class="meta-item"><dt>源码来源</dt><dd>{{ selected.evidenceSchemaVersion === 1 ? '历史固定提交，仅供查看' : '宿主当前工作区' }}</dd></div><div class="meta-item"><dt>模型</dt><dd>{{ selected.evidenceSchemaVersion === 1 ? '按历史报告展示' : '沿用宿主设置，实际标识可能未知' }}</dd></div></dl>
      <p v-if="selected.blockReason" class="notice">{{ reasons[selected.blockReason] ?? selected.blockReason }}</p>
      <p v-if="selected.contentExpired" class="notice">分析内容已过期，保留任务及摘要。</p>
      <template v-if="selected.state === 'READY' && selected.evidenceSchemaVersion === 2"><p>在当前项目中使用已安装 Skill、已配置平台地址和 Worker 凭据的宿主输入：</p><h4>仅分析</h4><pre class="analysis-code">{{ command }}</pre><h4>明确请求本地修复</h4><pre class="analysis-code">{{ repairCommand }}</pre><p class="muted">无需登记 APK 提交或仓库映射。当前代码无法证明历史版本根因；宿主自行读取当前代码，明确修复时自行修改和验证；Python 管理证据、租约和回传。修改与测试是宿主报告，源码片段仅在提交时核对当前位置。</p></template><p v-else-if="selected.evidenceSchemaVersion === 1" class="notice">旧任务仅供历史查看，请创建当前代码任务。</p>
      <div class="analysis-actions">
        <button v-if="canCreate && selected.state === 'BLOCKED' && !selected.evidenceId" class="button" :disabled="writing" @click="action('recheck')">重新检查前提</button>
        <button v-if="canCreate && ['FAILED', 'CANCELLED'].includes(selected.state) && selected.evidenceSchemaVersion === 2 && selected.evidenceId && !selected.contentExpired" class="button" :disabled="writing || runsLoading || runs.some(item => !item.run.stopConfirmed)" @click="action('retry')">显式重试</button>
        <button v-if="['BLOCKED', 'READY', 'RUNNING', 'CANCELLING'].includes(selected.state) && canCreate && (canManage || selected.createdBy === userId)" class="button" :disabled="writing" @click="action('cancel')">取消任务</button>
      </div>
      <article v-for="item in runs" :key="item.run.runId" class="analysis-run">
        <h3>第 {{ item.run.attempt }} 次尝试 · {{ item.run.state }}</h3>
        <p v-if="!item.run.stopConfirmed" class="notice notice-warning">本次执行停止尚未确认，不能重试；材料工具关闭不能证明宿主模型已停止。</p>
        <p v-if="item.run.hostStopState === 'UNKNOWN'" class="muted">本地材料工具：{{ item.run.localToolsStopped ? '已关闭' : '尚未确认关闭' }} · 宿主停止：未知</p>
        <p v-if="item.run.errorCode" class="field-error">{{ reasons[item.run.errorCode] ?? item.run.errorCode }}</p>
        <form v-if="canManage && !item.run.stopConfirmed && (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(item.run.state) || selected.state === 'CANCELLING')" @submit.prevent="confirmStop(item.run.runId)"><label>实际核验本次分析结束的依据<input v-model="stopBasis" maxlength="2000" required placeholder="记录本地工具关闭及本次宿主分析结束的实际依据"></label><button class="button" :disabled="writing || !stopBasis.trim()">已核验环境停止</button></form>
        <p v-if="item.contentExpired" class="notice">此结果内容已过期。</p>
        <template v-else-if="item.result">
          <h3>{{ item.result.conclusion === 'INSUFFICIENT_EVIDENCE' ? '证据不足' : '根因候选' }}</h3><p class="analysis-text">{{ item.result.summary }}</p>
          <p v-if="item.result.execution.mode === 'HOST_AGENT'">宿主 {{ item.result.execution.host ?? '未知' }}（{{ item.result.execution.metadataSource === 'HOST_REPORTED' ? '自报信息' : '来源未知' }}） · 模型 {{ item.result.execution.modelId ?? '未知' }} · Python {{ item.result.execution.toolVersion }}</p><p v-else>历史模型 {{ item.result.execution.providerId }} / {{ item.result.execution.modelId }} · OpenCode {{ item.result.execution.opencodeVersion }}</p>
          <p>输入 {{ item.result.usage.inputTokens ?? '未知' }} · 输出 {{ item.result.usage.outputTokens ?? '未知' }} · 缓存读取 {{ item.result.usage.cacheReadTokens ?? '未知' }} · 费用未知</p>
          <template v-if="item.result.schemaVersion === 4"><p>宿主直接分析 · Run <code>{{ item.result.runId }}</code> · 当前代码与历史 APK 可能不一致</p><section v-if="item.result.repair" class="analysis-outcome"><h4>本地修改：{{ repairStates[item.result.repair.status] }}</h4><p>来源：宿主报告，Python 不执行或独立验证修改。</p><p>{{ item.result.repair.reason }}</p><ul v-if="item.result.repair.files.length"><li v-for="file in item.result.repair.files" :key="file.path"><code>{{ file.path }}</code><p>{{ file.summary }}</p></li></ul></section><section v-if="item.result.verification" class="analysis-outcome"><h4>验证：{{ verificationStates[item.result.verification.status] }}</h4><p>来源：宿主报告；未独立核验测试或验证后工作区一致性。</p><p>{{ item.result.verification.reason }}</p><div v-for="(check, index) in item.result.verification.commands" :key="index"><pre class="analysis-code">{{ check.command }}</pre><p>退出码 {{ check.exitCode }}</p><p class="analysis-text">{{ check.summary }}</p></div></section><p class="muted">报告保存成功、代码修改完成和验证通过分别判断。</p></template>
          <template v-else-if="item.result.schemaVersion === 3"><p>历史分析快照 <code>{{ item.result.snapshotId }}</code> · 当前代码与历史 APK 可能不一致</p><section v-if="item.result.repair" class="analysis-outcome"><h4>本地修改：{{ repairStates[item.result.repair.status] }}</h4><p>{{ item.result.repair.reason }}</p><ul v-if="item.result.repair.files.length"><li v-for="file in item.result.repair.files" :key="file.path"><code>{{ file.path }}</code> · {{ file.beforeSha256 ? '修改' : '新建' }}<small class="analysis-text">写后摘要 {{ file.afterSha256 }}</small></li></ul></section><section v-if="item.result.verification" class="analysis-outcome"><h4>验证：{{ verificationStates[item.result.verification.status] }}</h4><p>来源：宿主报告 · 材料一致性：{{ item.result.verification.workspaceUnchanged ? '相关文件未变化' : '相关文件已变化，不能证明当前工作区通过' }}</p><p>{{ item.result.verification.reason }}</p><div v-for="(check, index) in item.result.verification.commands" :key="index"><pre class="analysis-code">{{ check.command }}</pre><p>退出码 {{ check.exitCode }}</p><p class="analysis-text">{{ check.summary }}</p></div></section><p class="muted">报告保存成功、代码修改完成和验证通过是独立事实。</p></template><p v-else>历史源码提交 <code>{{ item.result.commitSha }}</code>，旧模式仅供查看。</p>
          <div v-for="(candidate, index) in item.result.candidates" :key="index"><h4>{{ candidate.title }}</h4><p class="analysis-text">{{ candidate.reason }}</p><small>证据：{{ candidate.evidenceRefs.join('、') }}</small></div>
          <div v-for="(source, index) in item.result.sourceRefs" :key="index"><h4>{{ source.path }}:{{ source.startLine }}–{{ source.endLine }}</h4><p class="muted">{{ item.result.schemaVersion === 4 ? '片段来源：宿主报告；不证明宿主此前实际读取' : item.result.schemaVersion === 3 ? '历史引用来自修改前的当前代码快照' : '历史引用来自原固定提交' }}，明确凭据值已脱敏，行号保留。</p><p v-if="item.result.schemaVersion === 4">提交时当前位置核对：{{ source.currentCheck === 'CURRENT_MATCH' ? '与当前代码相符' : source.currentCheck === 'CURRENT_DIFFERENT' ? '与当前代码不同（可能已修改）' : '无法核验' }}</p><pre class="analysis-code">{{ source.snippet }}</pre><small>片段 SHA-256 {{ source.snippetSha256 }}</small></div>
          <template v-for="(entries, label) in { '未知项': item.result.unknowns, '风险': item.result.risks, '修复建议（未执行）': item.result.fixSuggestions, '验证建议（未执行）': item.result.validationSuggestions }" :key="label"><h4 v-if="entries.length">{{ label }}</h4><ul v-if="entries.length"><li v-for="(entry, index) in entries" :key="index" class="analysis-text">{{ entry }}</li></ul></template>
        </template>
      </article>
      <div v-if="runs.length || runPage > 0" class="analysis-actions"><button class="button" :disabled="runPage === 0" @click="loadRuns(runPage - 1)">上次尝试页</button><span>尝试第 {{ runPage + 1 }} 页</span><button class="button" :disabled="runs.length < 20 || runPage >= 1000" @click="loadRuns(runPage + 1)">下次尝试页</button></div>
    </template>
  </section>
</template>

<style scoped>
.analysis-panel { margin-top: 20px; }
/* 外层卡片已提供内边距，标题与正文共享左侧基线。 */
.analysis-panel > .panel-header { padding: 0 0 16px; flex-wrap: wrap; align-items: flex-start; }
.analysis-panel > .panel-header > div { flex: 1 1 320px; min-width: 0; }
.analysis-panel > .panel-header > .button { flex-shrink: 0; }
/* 复用元信息样式，去除默认定义列表缩进并允许长标识换行。 */
.analysis-meta .meta-item { min-width: 0; }
.analysis-meta dd code { overflow-wrap: anywhere; }
.analysis-actions, .analysis-task-list { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin: 14px 0; }
.analysis-code { background: var(--color-bg, #f7f9fc); padding: 14px; overflow: auto; white-space: pre; border-radius: 8px; }
.analysis-text { white-space: pre-wrap; overflow-wrap: anywhere; }
.analysis-run { border-top: 1px solid var(--color-border, #e4e8f0); margin-top: 18px; padding-top: 16px; }
.analysis-outcome { margin: 16px 0; }
.analysis-outcome li small { display: block; }
.analysis-run input { display: block; width: 100%; margin: 8px 0; }
</style>
