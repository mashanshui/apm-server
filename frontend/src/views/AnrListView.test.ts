import { createPinia, setActivePinia } from 'pinia'
import { reactive } from 'vue'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { anrApi } from '../api/anrApi'
import AnrListView from './AnrListView.vue'

/** 可变路由用于验证应用切换和旧请求。 */
const routeState = vi.hoisted(() => ({ params: { appId: 'app-1' }, query: {} as Record<string, string>, fullPath: '/apps/app-1/anrs' }))
const route = reactive(routeState)
const router = vi.hoisted(() => ({ push: vi.fn() }))
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => router,
  RouterLink: { props: ['to'], template: '<a><slot /></a>' } }))
vi.mock('../api/anrApi', () => ({ anrApi: { overview: vi.fn(), events: vi.fn() } }))
enableAutoUnmount(afterEach)

/** 构造一个列表页摘要。 */
function page(eventId = 'event-1', nextCursor: string | null = null) {
  return { appId: 'app-1', from: '2026-11-20T00:00:00Z', to: '2026-11-21T00:00:00Z', nextCursor,
    events: [{ eventId, occurredAt: '2026-11-20T01:00:00Z', appVersion: '1.0', versionCode: 0,
      deviceModel: 'Pixel', source: 'message_timeout' as const }] }
}

describe('ANR 列表', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.resetAllMocks()
    route.params.appId = 'app-1'; route.query = {}; route.fullPath = '/apps/app-1/anrs'
    vi.mocked(anrApi.overview).mockResolvedValue({ appId: 'app-1', from: '', to: '', eventCount: 1, deviceCount: 0 })
    vi.mocked(anrApi.events).mockResolvedValue(page())
  })

  /** 列表、合法零值和筛选均使用后端数据。 */
  it('显示统计、来源并提交筛选', async () => {
    const wrapper = mount(AnrListView, { global: { stubs: { AppLayout: { template: '<div><slot /></div>' }, RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()
    expect(wrapper.text()).toContain('event-1')
    expect(wrapper.text()).toContain('主线程消息超时')
    expect(wrapper.text()).toContain('1.0 / 0')
    expect(wrapper.text()).toContain('影响设备')
    await wrapper.get('#anr-version').setValue('2.0')
    await wrapper.get('#anr-source').setValue('process_error_state')
    await wrapper.get('form').trigger('submit')
    expect(router.push).toHaveBeenCalledWith(expect.objectContaining({ name: 'anr-list',
      query: expect.objectContaining({ appVersion: '2.0', source: 'process_error_state' }) }))
  })

  /** 续页以服务端返回的时间窗绑定游标，不丢失第一页。 */
  it('加载更多并沿用服务端时间窗', async () => {
    vi.mocked(anrApi.events).mockResolvedValueOnce(page('event-1', 'cursor-1')).mockResolvedValueOnce(page('event-2'))
    const wrapper = mount(AnrListView, { global: { stubs: { AppLayout: { template: '<div><slot /></div>' }, RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '加载更多')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('event-1')
    expect(wrapper.text()).toContain('event-2')
    expect(vi.mocked(anrApi.events).mock.calls[1][1]).toEqual(expect.objectContaining({ from: '2026-11-20T00:00:00Z', to: '2026-11-21T00:00:00Z' }))
    expect(vi.mocked(anrApi.events).mock.calls[1][2]).toBe('cursor-1')
  })

  /** 空结果和请求失败分别展示，不伪造事件。 */
  it('区分空列表和失败', async () => {
    vi.mocked(anrApi.events).mockResolvedValueOnce({ ...page(), events: [] })
    const wrapper = mount(AnrListView, { global: { stubs: { AppLayout: { template: '<div><slot /></div>' }, RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()
    expect(wrapper.text()).toContain('当前筛选范围没有 ANR 事件')
    vi.mocked(anrApi.events).mockRejectedValueOnce(new Error('failed'))
    route.fullPath = '/apps/app-1/anrs?reload=1'
    await flushPromises()
    expect(wrapper.text()).toContain('请求失败')
  })

  /** 尚未完成的列表请求保持加载提示，避免先显示错误空状态。 */
  it('请求期间显示加载状态', async () => {
    vi.mocked(anrApi.events).mockImplementationOnce(() => new Promise(() => {}))
    const wrapper = mount(AnrListView, { global: { stubs: { AppLayout: { template: '<div><slot /></div>' }, RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()
    expect(wrapper.text()).toContain('正在加载 ANR 事件')
    expect(wrapper.text()).not.toContain('当前筛选范围没有 ANR 事件')
  })

  /** 上一个应用响应迟到也不能覆盖当前应用的数据。 */
  it('忽略旧应用的迟到响应', async () => {
    let resolveOld!: (value: ReturnType<typeof page>) => void
    vi.mocked(anrApi.events).mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
      .mockResolvedValueOnce(page('new-event'))
    const wrapper = mount(AnrListView, { global: { stubs: { AppLayout: { template: '<div><slot /></div>' }, RouterLink: { template: '<a><slot /></a>' } } } })
    route.params.appId = 'app-2'; route.fullPath = '/apps/app-2/anrs'
    await flushPromises()
    resolveOld(page('old-event'))
    await flushPromises()
    expect(wrapper.text()).toContain('new-event')
    expect(wrapper.text()).not.toContain('old-event')
  })
})
