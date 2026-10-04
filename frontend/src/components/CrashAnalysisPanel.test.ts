import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import CrashAnalysisPanel from './CrashAnalysisPanel.vue'
import { analysisApi } from '../api/analysisApi'
import { ApiError } from '../api/http'
import type { AnalysisRunDetail, AnalysisTask } from '../types/analysis'

vi.mock('../api/analysisApi', () => ({ analysisApi: { list: vi.fn(), runs: vi.fn(), create: vi.fn(), action: vi.fn(), confirmStopped: vi.fn() } }))
/** 无敏感任务元数据。 */
const task: AnalysisTask = { taskId: '11111111-1111-4111-8111-111111111111', appId: 'app-a', eventId: 'event-a', fingerprint: 'fingerprint',
  createdBy: 'user-a', createdAt: '2026-10-01T00:00:00Z', state: 'READY', blockReason: null, evidenceSchemaVersion: 2,
  evidenceId: 'evidence', evidenceSha256: 'a'.repeat(64), contentExpired: false }
/** 模拟 API 类型与服务端契约保持一致。 */
const api = vi.mocked(analysisApi)

/** 显式完成迟到响应，验证应用/事件切换隔离。 */
function deferred<T>() {
  /** 当前响应完成器。 */
  let resolve!: (value: T) => void
  /** 等待测试控制的网络请求。 */
  const promise = new Promise<T>(complete => { resolve = complete })
  return { promise, resolve }
}

/** 挂载 Developer 默认面板，不要求管理员权限。 */
function panel(canCreate = true) {
  return mount(CrashAnalysisPanel, { props: { appId: 'app-a', eventId: 'event-a', canCreate, canManage: false, userId: 'user-a' } })
}

describe('CrashAnalysisPanel', () => {
  beforeEach(() => { vi.resetAllMocks(); api.list.mockResolvedValue([]); api.runs.mockResolvedValue([]) })
  afterEach(() => { vi.useRealTimers() })

  it('lets Developer create a task and shows a command without any credential', async () => {
    /** 网络未知时按钮重试复用同一 UUID。 */
    api.create.mockRejectedValueOnce(new ApiError({ status: 0, message: 'offline' })).mockResolvedValue(task)
    const wrapper = panel()
    await flushPromises()
    await wrapper.get('[data-testid="create-analysis"]').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('同一按钮重试')
    api.list.mockResolvedValue([task])
    await wrapper.get('[data-testid="create-analysis"]').trigger('click'); await flushPromises()
    expect(api.create.mock.calls[0]?.[2]).toEqual(api.create.mock.calls[1]?.[2])
    expect(wrapper.text()).toContain(`使用 apm-crash-analyze 分析任务 ${task.taskId}`)
    expect(wrapper.text()).toContain('宿主当前项目')
    expect(wrapper.text()).toContain(`分析并修复任务 ${task.taskId}`)
    expect(wrapper.text()).not.toContain('apm_aw_')
    expect(wrapper.text()).not.toContain('api_key')
    wrapper.unmount()
  })

  it('keeps Viewer read only even when it used to be the creator', async () => {
    api.list.mockResolvedValue([task])
    const wrapper = panel(false)
    await flushPromises()
    expect(wrapper.find('[data-testid="create-analysis"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('取消任务')
    expect(wrapper.text()).not.toContain('重新检查前提')
    expect(api.create).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('shows prerequisite blocks and sends explicit recheck or cancel', async () => {
    /** 没有证据的阻断任务不显示正在调用模型。 */
    api.list.mockResolvedValue([{ ...task, state: 'BLOCKED', evidenceId: null, blockReason: 'PREPARATION_FAILED' }])
    const wrapper = panel()
    await flushPromises()
    expect(wrapper.text()).toContain('证据准备失败')
    expect(wrapper.text()).not.toContain('apm-analysis run')
    /** 用户明确选择重新准备或取消，后端仍核对权限。 */
    const recheck = wrapper.findAll('button').find(button => button.text() === '重新检查前提')!
    await recheck.trigger('click'); await flushPromises()
    expect(api.action).toHaveBeenCalledWith('app-a', task.taskId, 'recheck', expect.any(AbortSignal))
    await wrapper.findAll('button').find(button => button.text() === '取消任务')!.trigger('click'); await flushPromises()
    expect(api.action).toHaveBeenCalledWith('app-a', task.taskId, 'cancel', expect.any(AbortSignal))
    wrapper.unmount()
  })

  it('discards old event list and delayed creation after switching applications', async () => {
    /** 第一页请求不服从 Abort，仍必须被代次隔离。 */
    const old = deferred<AnalysisTask[]>()
    api.list.mockReturnValueOnce(old.promise).mockResolvedValue([])
    const wrapper = panel()
    await wrapper.setProps({ appId: 'app-b', eventId: 'event-b' }); await flushPromises()
    old.resolve([task]); await flushPromises()
    expect(wrapper.text()).not.toContain(task.taskId)
    /** 第一应用写入已抵达服务端时，迟到返回不能污染第二应用。 */
    const write = deferred<AnalysisTask>()
    api.create.mockReturnValueOnce(write.promise)
    await wrapper.get('[data-testid="create-analysis"]').trigger('click')
    await wrapper.setProps({ appId: 'app-c', eventId: 'event-c' }); await flushPromises()
    write.resolve(task); await flushPromises()
    expect(wrapper.text()).not.toContain(task.taskId)
    expect(api.list.mock.calls.at(-1)?.slice(0, 2)).toEqual(['app-c', 'event-c'])
    wrapper.unmount()
  })

  it('escapes malicious result text and distinguishes insufficient evidence from expired content', async () => {
    /** 所有恶意文本只是普通字符串，不能交给 v-html。 */
    api.list.mockResolvedValue([{ ...task, state: 'SUCCEEDED' }])
    const run: AnalysisRunDetail = { run: { runId: 'run', taskId: task.taskId, attempt: 1, state: 'SUCCEEDED', taskState: 'SUCCEEDED', stopConfirmed: true, errorCode: null },
      contentExpired: false, result: { schemaVersion: 1, evidenceId: 'evidence', commitSha: 'a'.repeat(40), conclusion: 'INSUFFICIENT_EVIDENCE',
        summary: '<script>attack()</script>', candidates: [], sourceRefs: [{ path: 'Example.kt', startLine: 1, endLine: 1, snippet: '<img src=x onerror=attack()>', snippetSha256: 'a'.repeat(64) }],
        unknowns: ['缺少触发条件'], risks: [], fixSuggestions: [], validationSuggestions: ['建议复现'],
        execution: { providerId: 'deepseek', modelId: 'deepseek-flash', opencodeVersion: '1.18.34' },
        usage: { inputTokens: 0, outputTokens: null, cacheReadTokens: null, cacheWriteTokens: null, cost: null } } }
    api.runs.mockResolvedValue([run])
    const wrapper = panel(false)
    await flushPromises()
    expect(wrapper.text()).toContain('证据不足'); expect(wrapper.text()).toContain('输入 0')
    expect(wrapper.find('script').exists()).toBe(false); expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).toContain('<script>attack()</script>')
    api.runs.mockResolvedValue([{ ...run, result: null, contentExpired: true }])
    await wrapper.findAll('button').find(button => button.text() === '刷新历史')!.trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('此结果内容已过期'); expect(wrapper.text()).not.toContain('attack()')
    wrapper.unmount()
  })

  it('polls active tasks and stops after terminal status', async () => {
    vi.useFakeTimers()
    api.list.mockResolvedValueOnce([task]).mockResolvedValue([{ ...task, state: 'SUCCEEDED' }])
    const wrapper = panel(false)
    await flushPromises()
    await vi.advanceTimersByTimeAsync(5000); await flushPromises()
    expect(api.list).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(10000); await flushPromises()
    expect(api.list).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('paginates on the server and disables retry while stop remains unknown', async () => {
    api.list.mockResolvedValue(Array.from({length:20},(_,index)=>({...task, taskId:'task-'+index,state:'FAILED' as const})))
    api.runs.mockResolvedValue([{run:{runId:'run',taskId:'task-0',attempt:1,state:'FAILED',taskState:'FAILED',stopConfirmed:false,errorCode:'LEASE_EXPIRED'},result:null,contentExpired:false}])
    const wrapper = panel()
    await flushPromises()
    expect(wrapper.findAll('button').find(button=>button.text()==='显式重试')!.attributes('disabled')).toBeDefined()
    api.list.mockResolvedValue([])
    await wrapper.findAll('button').find(button=>button.text()==='下一页')!.trigger('click'); await flushPromises()
    expect(api.list.mock.calls.at(-1)?.slice(0,3)).toEqual(['app-a','event-a',1])
    expect(wrapper.text()).toContain('任务第 2 页')
    expect(wrapper.findAll('button').find(button=>button.text()==='下一页')!.attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('shows completed report separately from unknown host stop', async () => {
    /** 工具关闭是局部事实，不替代宿主停止；成功报告仍可查看。 */
    api.list.mockResolvedValue([{ ...task, state: 'SUCCEEDED' }])
    api.runs.mockResolvedValue([{ run: { runId: 'host', taskId: task.taskId, attempt: 1, state: 'SUCCEEDED', taskState: 'SUCCEEDED', stopConfirmed: false, localToolsStopped: true, hostStopState: 'UNKNOWN', errorCode: null }, contentExpired: false, result: { schemaVersion: 2, evidenceId: 'evidence', commitSha: 'a'.repeat(40), conclusion: 'INSUFFICIENT_EVIDENCE', summary: '缺少输入', candidates: [], sourceRefs: [], unknowns: ['输入未知'], risks: [], fixSuggestions: [], validationSuggestions: [], execution: { mode: 'HOST_AGENT', host: 'Codex', modelId: null, metadataSource: 'HOST_REPORTED', toolVersion: '0.2.0' }, usage: { inputTokens: null, outputTokens: null, cacheReadTokens: null, cacheWriteTokens: null, cost: null } } }])
    const wrapper = panel()
    await flushPromises()
    expect(wrapper.text()).toContain('缺少输入')
    expect(wrapper.text()).toContain('本地材料工具：已关闭')
    expect(wrapper.text()).toContain('宿主停止：未知')
    expect(wrapper.text()).toContain('模型 未知')
    expect(wrapper.text()).toContain('自报信息')
    expect(wrapper.text()).not.toContain('显式重试')
    wrapper.unmount()
  })

  it('clears data and stops polling when the session expires', async () => {
    vi.useFakeTimers()
    api.list.mockResolvedValue([task])
    const wrapper = panel(false)
    await flushPromises()
    window.dispatchEvent(new CustomEvent('apm:auth-expired'))
    await flushPromises()
    expect(wrapper.text()).not.toContain(task.taskId)
    await vi.advanceTimersByTimeAsync(10000)
    expect(api.list).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('shows applied code separately from failed host validation and safely escapes commands', async () => {
    api.list.mockResolvedValue([{ ...task, state: 'SUCCEEDED' }]) // 报告已保存。
    api.runs.mockResolvedValue([{ run: { runId: 'current', taskId: task.taskId, attempt: 1, state: 'SUCCEEDED', taskState: 'SUCCEEDED', stopConfirmed: false, hostStopState: 'UNKNOWN', errorCode: null }, contentExpired: false,
      result: { schemaVersion: 3, evidenceId: 'evidence', snapshotId: 'snapshot-current', conclusion: 'ROOT_CAUSE_CANDIDATE', summary: '当前代码候选', candidates: [], sourceRefs: [], unknowns: ['历史版本未知'], risks: [], fixSuggestions: [], validationSuggestions: [], execution: { mode: 'HOST_AGENT', host: 'Codex', modelId: null, metadataSource: 'HOST_REPORTED', toolVersion: '0.3.0' }, usage: { inputTokens: null, outputTokens: null, cacheReadTokens: null, cacheWriteTokens: null, cost: null }, repair: { status: 'APPLIED', files: [{ path: 'Example.kt', beforeSha256: 'a'.repeat(64), afterSha256: 'b'.repeat(64) }], reason: '有限修改' }, verification: { status: 'FAILED', metadataSource: 'HOST_REPORTED', commands: [{ command: '<img src=x onerror=attack()>', exitCode: 1, summary: '<script>attack()</script>' }], reason: '测试失败', workspaceUnchanged: true } } }])
    const wrapper = panel()
    await flushPromises()
    expect(wrapper.text()).toContain('本地修改：已修改代码')
    expect(wrapper.text()).toContain('验证：验证失败')
    expect(wrapper.text()).toContain('来源：宿主报告')
    expect(wrapper.text()).toContain('snapshot-current')
    expect(wrapper.text()).toContain('历史版本未知')
    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('宿主验证通过')
    wrapper.unmount()
  })

  it('keeps old frozen tasks read only and offers no execution retry', async () => {
    api.list.mockResolvedValue([{ ...task, state: 'CANCELLED', evidenceSchemaVersion: 1 }]) // 旧终态。
    const wrapper = panel()
    await flushPromises()
    expect(wrapper.text()).toContain('旧任务仅供历史查看')
    expect(wrapper.text()).toContain('历史固定提交，仅供查看')
    expect(wrapper.text()).not.toContain('宿主当前工作区')
    expect(wrapper.text()).not.toContain('显式重试')
    expect(wrapper.text()).not.toContain(`分析并修复任务 ${task.taskId}`)
    wrapper.unmount()
  })

  it('labels direct host edits, test results and current references without snapshot guarantees', async () => {
    /** 版本 4 只呈现宿主报告和提交时匹配结果，UNKNOWN 仍阻止新尝试。 */
    api.list.mockResolvedValue([{ ...task, state: 'SUCCEEDED' }])
    api.runs.mockResolvedValue([{ run: { runId: 'direct-run', taskId: task.taskId, attempt: 1, state: 'SUCCEEDED', taskState: 'SUCCEEDED', stopConfirmed: false, errorCode: null, localToolsStopped: true, hostStopState: 'UNKNOWN' }, contentExpired: false,
      result: { schemaVersion: 4, evidenceId: 'evidence', runId: 'direct-run', conclusion: 'ROOT_CAUSE_CANDIDATE', summary: '当前代码', candidates: [],
        sourceRefs: [{ path: 'Example.kt', startLine: 1, endLine: 1, snippet: '<script>bad()</script>', snippetSha256: 'a'.repeat(64), metadataSource: 'HOST_REPORTED', currentCheck: 'CURRENT_DIFFERENT' }],
        unknowns: [], risks: [], fixSuggestions: [], validationSuggestions: [], execution: { mode: 'HOST_AGENT', toolVersion: '0.4.0', host: 'Codex', metadataSource: 'HOST_REPORTED' },
        repair: { status: 'APPLIED', metadataSource: 'HOST_REPORTED', files: [{ path: 'Example.kt', summary: '边界判断' }], reason: '' },
        verification: { status: 'NOT_RUN', metadataSource: 'HOST_REPORTED', commands: [], reason: '缺少 Android 环境' },
        usage: { inputTokens: null, outputTokens: null, cacheReadTokens: null, cacheWriteTokens: null, cost: null } } }])
    const wrapper = panel()
    await flushPromises()
    expect(wrapper.text()).toContain('宿主直接分析')
    expect(wrapper.text()).toContain('未独立核验测试或验证后工作区一致性')
    expect(wrapper.text()).toContain('与当前代码不同')
    expect(wrapper.text()).toContain('验证：尚未验证')
    expect(wrapper.text()).not.toContain('分析快照')
    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.findAll('button').some(button => button.text().includes('重试') && !button.attributes('disabled'))).toBe(false)
    wrapper.unmount()
  })

})
