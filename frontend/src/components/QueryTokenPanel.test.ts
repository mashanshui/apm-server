import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import QueryTokenPanel from './QueryTokenPanel.vue'
import { queryTokenApi } from '../api/queryTokenApi'
import { ApiError } from '../api/http'
import type { QueryTokenCreated, QueryTokenMetadata } from '../types/queryToken'

vi.mock('../api/queryTokenApi', () => ({
  queryTokenApi: { list: vi.fn(), create: vi.fn(), revoke: vi.fn() },
}))

/** 管理列表只使用无完整秘密的元数据。 */
const metadata: QueryTokenMetadata = {
  id: 'token-a', name: '值班分析', displayPrefix: 'apm_qt_abcdef', scope: 'apm:read',
  createdBy: 'user-a', createdAt: '2026-09-28T00:00:00Z',
  expiresAt: '2026-12-27T00:00:00Z', revokedAt: null, status: 'ACTIVE',
}
/** 完整值只存在模拟创建响应内。 */
const created: QueryTokenCreated = { metadata, token: 'apm_qt_created_secret' }
/** 测试每个应用的空列表初始状态。 */
const emptyPage = { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 }
/** 强类型化模拟 API 调用。 */
const api = vi.mocked(queryTokenApi)

/** 创建一个可手动完成的响应，验证迟到结果隔离。 */
function deferred<T>() {
  /** Promise 成功回调，由测试控制时机。 */
  let resolve!: (value: T) => void
  /** 待完成的模拟网络请求。 */
  const promise = new Promise<T>((complete) => { resolve = complete })
  return { promise, resolve }
}

describe('QueryTokenPanel', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    api.list.mockResolvedValue(emptyPage)
    localStorage.clear()
    sessionStorage.clear()
  })

  it('shows loading, empty and retryable list failure without management controls for ordinary members', async () => {
    /** 初始请求由测试手动完成。 */
    const pending = deferred<typeof emptyPage>()
    api.list.mockReturnValueOnce(pending.promise)
    const wrapper = mount(QueryTokenPanel, { props: { appId: 'app-a', canManage: true } })
    expect(wrapper.text()).toContain('正在加载 Token')
    pending.resolve(emptyPage)
    await flushPromises()
    expect(wrapper.text()).toContain('暂无查询 Token')
    api.list.mockRejectedValueOnce(new Error('offline'))
    await wrapper.get('.query-token-list-header button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('请求失败')
    await wrapper.setProps({ canManage: false })
    expect(wrapper.find('.query-token-panel').exists()).toBe(false)
    wrapper.unmount()
  })

  it('creates only once, copies once-visible secret, and clears it on close', async () => {
    /** 双击期间保持创建请求在途。 */
    const pending = deferred<QueryTokenCreated>()
    api.create.mockReturnValueOnce(pending.promise)
    const writeText = vi.fn().mockRejectedValue(new Error('clipboard blocked'))
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    const wrapper = mount(QueryTokenPanel, { props: { appId: 'app-a', canManage: true } })
    await flushPromises()
    await wrapper.get('#query-token-name').setValue('值班分析')
    await wrapper.get('#query-token-expiry').setValue('30')
    await wrapper.get('.query-token-create').trigger('submit')
    await wrapper.get('.query-token-create').trigger('submit')
    expect(api.create).toHaveBeenCalledTimes(1)
    expect(api.create).toHaveBeenCalledWith('app-a', { name: '值班分析', expiresInDays: 30 }, expect.any(AbortSignal))
    pending.resolve(created)
    await flushPromises()
    expect(wrapper.text()).toContain(created.token)
    expect(JSON.stringify(localStorage)).not.toContain(created.token)
    expect(JSON.stringify(sessionStorage)).not.toContain(created.token)
    await wrapper.get('.query-token-result .button-primary').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('复制失败')
    await wrapper.get('.query-token-result .button:not(.button-primary)').trigger('click')
    expect(wrapper.text()).not.toContain(created.token)
    wrapper.unmount()
  })

  it('does not show an old app create response after switching apps or session expiry', async () => {
    /** 应用 A 的请求刻意在切换到 B 后完成。 */
    const oldResponse = deferred<QueryTokenCreated>()
    api.create.mockReturnValueOnce(oldResponse.promise)
    const wrapper = mount(QueryTokenPanel, { props: { appId: 'app-a', canManage: true } })
    await flushPromises()
    await wrapper.get('#query-token-name').setValue('A')
    await wrapper.get('.query-token-create').trigger('submit')
    await wrapper.setProps({ appId: 'app-b' })
    oldResponse.resolve(created)
    await flushPromises()
    expect(wrapper.text()).not.toContain(created.token)
    expect(api.list).toHaveBeenCalledWith('app-b', 0, 20, expect.any(AbortSignal))

    api.create.mockResolvedValueOnce(created)
    await wrapper.get('#query-token-name').setValue('B')
    await wrapper.get('.query-token-create').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain(created.token)
    window.dispatchEvent(new CustomEvent('apm:auth-expired'))
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).not.toContain(created.token)
    wrapper.unmount()
  })

  it('warns that a lost creation response cannot be recovered and confirms revocation', async () => {
    api.list.mockResolvedValue({ items: [metadata], page: 0, size: 20, totalItems: 1, totalPages: 1 })
    api.create.mockRejectedValueOnce(new ApiError({ status: 0, message: 'offline' }))
    api.revoke.mockResolvedValueOnce(undefined)
    const wrapper = mount(QueryTokenPanel, { props: { appId: 'app-a', canManage: true } })
    await flushPromises()
    await wrapper.get('#query-token-name').setValue('值班分析')
    await wrapper.get('.query-token-create').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('创建结果不确定')
    await wrapper.get('.query-token-list tbody button').trigger('click')
    await wrapper.get('.query-token-list tbody button').trigger('click')
    await flushPromises()
    expect(api.revoke).toHaveBeenCalledWith('app-a', 'token-a', expect.any(AbortSignal))
    expect(wrapper.text()).not.toContain('apm_qt_created_secret')
    wrapper.unmount()
  })

  it('drops a pending creation response after unmount', async () => {
    /** 服务端可能完成创建，但卸载后的页面不能重新持有完整值。 */
    const lateResponse = deferred<QueryTokenCreated>()
    api.create.mockReturnValueOnce(lateResponse.promise)
    const wrapper = mount(QueryTokenPanel, { props: { appId: 'app-a', canManage: true } })
    await flushPromises()
    await wrapper.get('#query-token-name').setValue('late')
    await wrapper.get('.query-token-create').trigger('submit')
    wrapper.unmount()
    lateResponse.resolve(created)
    await flushPromises()
    expect(wrapper.text()).not.toContain(created.token)
  })
})
