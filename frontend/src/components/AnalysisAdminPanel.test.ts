import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import AnalysisAdminPanel from './AnalysisAdminPanel.vue'
import { analysisApi } from '../api/analysisApi'
import { ApiError } from '../api/http'
import type { WorkerMetadata } from '../types/analysis'

vi.mock('../api/analysisApi', () => ({ analysisApi: { workers: vi.fn(), createWorker: vi.fn(), revokeWorker: vi.fn() } }))
/** 完整秘密只出现在模拟创建结果，不能进入管理列表。 */
const worker: WorkerMetadata = { credentialId: 'worker-a', appId: 'app-a', name: '本地 Worker', displayPrefix: 'apm_aw_prefix', createdAt: '2026-10-01T00:00:00Z', expiresAt: '2099-10-31T00:00:00Z', revokedAt: null }
/** 模拟 API 按真实类型约束。 */
const api = vi.mocked(analysisApi)

/** 模拟未遵守 Abort 的迟到创建响应。 */
function deferred<T>() {
  /** Promise 成功回调。 */
  let resolve!: (value: T) => void
  /** 可延迟控制的响应。 */
  const promise = new Promise<T>(complete => { resolve = complete })
  return { promise, resolve }
}

/** 当前管理员面板。 */
function panel() { return mount(AnalysisAdminPanel, { props: { appId: 'app-a', canManage: true } }) }

describe('AnalysisAdminPanel', () => {
  beforeEach(() => { vi.resetAllMocks(); api.workers.mockResolvedValue([]); localStorage.clear(); sessionStorage.clear() })

  it('never exposes management or calls APIs for ordinary members', async () => {
    const wrapper = mount(AnalysisAdminPanel, { props: { appId: 'app-a', canManage: false } })
    await flushPromises()
    expect(wrapper.find('form').exists()).toBe(false); expect(api.workers).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('keeps only app credentials without build or model configuration', async () => {
    const wrapper = panel() // 当前应用管理员。
    await flushPromises()
    expect(wrapper.find('.analysis-build-form').exists()).toBe(false)
    for (const text of ['完整提交 SHA', '混淆状态', '构建源码登记', 'DeepSeek', 'performance']) expect(wrapper.text()).not.toContain(text)
    expect(api.workers).toHaveBeenCalledWith('app-a', 0, expect.any(AbortSignal))
    wrapper.unmount()
  })

  it('shows the full Worker secret only once and clears on close or auth expiry', async () => {
    api.createWorker.mockResolvedValue({ metadata: worker, credential: 'apm_aw_synthetic_secret' })
    const wrapper = panel()
    await flushPromises()
    await wrapper.get('#analysis-worker-name').setValue('本地 Worker')
    await wrapper.get('.analysis-worker-form').trigger('submit'); await flushPromises()
    expect(wrapper.text()).toContain('apm_aw_synthetic_secret')
    expect(JSON.stringify(localStorage)).not.toContain('apm_aw_synthetic_secret'); expect(JSON.stringify(sessionStorage)).not.toContain('apm_aw_synthetic_secret')
    await wrapper.findAll('button').find(button => button.text() === '关闭完整值')!.trigger('click')
    expect(wrapper.text()).not.toContain('apm_aw_synthetic_secret')
    await wrapper.get('#analysis-worker-name').setValue('另一个')
    await wrapper.get('.analysis-worker-form').trigger('submit'); await flushPromises()
    window.dispatchEvent(new Event('apm:auth-expired')); await flushPromises()
    expect(wrapper.text()).not.toContain('apm_aw_synthetic_secret')
    wrapper.unmount()
  })

  it('discards a delayed secret after application or role change', async () => {
    const late = deferred<{ metadata: WorkerMetadata; credential: string }>()
    api.createWorker.mockReturnValueOnce(late.promise)
    const wrapper = panel()
    await flushPromises()
    await wrapper.get('#analysis-worker-name').setValue('本地')
    await wrapper.get('.analysis-worker-form').trigger('submit')
    await wrapper.setProps({ appId: 'app-b' }); await flushPromises()
    late.resolve({ metadata: worker, credential: 'apm_aw_late_secret' }); await flushPromises()
    expect(wrapper.text()).not.toContain('apm_aw_late_secret')
    await wrapper.setProps({ canManage: false }); expect(wrapper.find('form').exists()).toBe(false)
    wrapper.unmount()
  })

  it('paginates server arrays and revokes only the current app credential', async () => {
    api.workers.mockResolvedValue(Array.from({ length: 20 }, (_, index) => ({ ...worker, credentialId: `worker-${index}` })))
    const wrapper = panel()
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '下一凭据页')!.trigger('click'); await flushPromises()
    expect(api.workers).toHaveBeenLastCalledWith('app-a', 1, expect.any(AbortSignal))
    await wrapper.findAll('button').find(button => button.text() === '撤销 Worker 凭据')!.trigger('click'); await flushPromises()
    expect(api.revokeWorker).toHaveBeenCalledWith('app-a', 'worker-0', expect.any(AbortSignal))
    wrapper.unmount()
  })
})
