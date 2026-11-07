import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import AppLayout from './AppLayout.vue'
import { appApi } from '../api/appApi'
import { useSessionStore } from '../stores/session'
import { reactive } from 'vue'

const routerMock = vi.hoisted(() => ({ push: vi.fn() }))
/** 在模块模拟前创建的初始路由数据。 */
const routeState = vi.hoisted(() => ({ name: 'crash-overview' as string, query: {} as Record<string, string> }))
/** 响应式路由替身，用于验证页面跳转后的自动展开。 */
const routeMock = reactive(routeState)

enableAutoUnmount(afterEach)

vi.mock('vue-router', () => ({
  useRoute: () => routeMock,
  useRouter: () => routerMock,
  RouterLink: { template: '<a><slot /></a>' },
}))

vi.mock('../api/appApi', () => ({
  appApi: {
    list: vi.fn(),
    get: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    getIngestCredential: vi.fn(),
  },
}))

const mockedAppApi = vi.mocked(appApi)
const appFixtures = [
  { appId: 'mobile-apm', name: '移动 APM', description: null, packageName: 'com.example.mobile', role: 'OWNER' as const, createdAt: '2026-08-25T00:00:00Z', updatedAt: '2026-08-25T00:00:00Z' },
  { appId: 'other-apm', name: '另一个应用', description: null, packageName: 'com.example.other', role: 'VIEWER' as const, createdAt: '2026-08-25T00:00:00Z', updatedAt: '2026-08-25T00:00:00Z' },
]

describe('AppLayout', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.resetAllMocks()
    routeMock.name = 'crash-overview'
    routeMock.query = {}
    useSessionStore().user = {
      id: 'user-1',
      email: 'test@example.com',
      displayName: '测试用户',
    }
  })

  it('loads the app switcher and navigates to the selected app', async () => {
    mockedAppApi.list.mockResolvedValue([
      {
        appId: 'mobile-apm',
        name: '移动 APM',
        description: null,
        packageName: 'com.example.mobile',
        role: 'OWNER',
        createdAt: '2026-08-25T00:00:00Z',
        updatedAt: '2026-08-25T00:00:00Z',
      },
      {
        appId: 'other-apm',
        name: '另一个应用',
        description: null,
        packageName: 'com.example.other',
        role: 'VIEWER',
        createdAt: '2026-08-25T00:00:00Z',
        updatedAt: '2026-08-25T00:00:00Z',
      },
    ])
    const wrapper = mount(AppLayout, {
      props: { appId: 'mobile-apm' },
      slots: { default: '<p>内容</p>' },
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })
    await flushPromises()

    expect(wrapper.get('.topbar-context').text()).toContain('移动 APM')
    expect(wrapper.get('.app-switcher select option:checked').text()).toBe('移动 APM')
    await wrapper.get('.app-switcher select').setValue('other-apm')
    expect(routerMock.push).toHaveBeenCalledWith({ name: 'crash-overview', params: { appId: 'other-apm' } })
  })

  it('从卡顿指标页切换应用时保留兼容指标筛选', async () => {
    routeMock.name = 'jank-metrics'
    routeMock.query = { from: '2026-08-15T00:00:00Z', to: '2026-08-16T00:00:00Z', metric: 'suspension_rate', interval: 'hour', dimension: 'scene', scene: 'feed' }
    mockedAppApi.list.mockResolvedValue(appFixtures)
    const wrapper = mount(AppLayout, {
      props: { appId: 'mobile-apm' },
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })
    await flushPromises()
    await wrapper.get('.app-switcher select').setValue('other-apm')
    expect(routerMock.push).toHaveBeenCalledWith(expect.objectContaining({
      name: 'jank-metrics', params: { appId: 'other-apm' },
      query: expect.objectContaining({ metric: 'suspension_rate', interval: 'day', dimension: 'deviceModel' }),
    }))
  })

  it('从卡顿事件详情切换应用时回到问题列表并删除详情标识', async () => {
    routeMock.name = 'jank-event-detail'
    routeMock.query = { from: '2026-08-15T00:00:00Z', to: '2026-08-16T00:00:00Z', fingerprint: 'old-fingerprint', cursor: 'old-cursor', scene: 'feed' }
    mockedAppApi.list.mockResolvedValue(appFixtures)
    const wrapper = mount(AppLayout, {
      props: { appId: 'mobile-apm' },
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })
    await flushPromises()
    await wrapper.get('.app-switcher select').setValue('other-apm')
    const target = routerMock.push.mock.calls[0][0]
    expect(target.name).toBe('jank-issues')
    expect(target.params).toEqual({ appId: 'other-apm' })
    expect(target.query.fingerprint).toBeUndefined()
    expect(target.query.cursor).toBeUndefined()
    expect(target.query.scene).toBe('feed')
  })

  it('在 Issue 深层页面选中卡顿问题子模块', () => {
    routeMock.name = 'jank-issue-events'
    mockedAppApi.list.mockResolvedValue(appFixtures)
    const wrapper = mount(AppLayout, {
      props: { appId: 'mobile-apm' },
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })
    const link = wrapper.findAll('.nav-link').find((item) => item.text().includes('卡顿问题分析'))
    expect(link?.classes()).toContain('router-link-active')
  })

  it('默认收起两个分组，点击可独立展开和再次收起且不跳转页面', async () => {
    mockedAppApi.list.mockResolvedValue(appFixtures)
    /** 带应用上下文的侧栏。 */
    const wrapper = mount(AppLayout, {
      attachTo: document.body,
      props: { appId: 'mobile-apm' },
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })
    /** 卡顿分组按钮。 */
    const jankToggle = wrapper.get('[aria-controls="jank-navigation"]')
    /** 内存分组按钮。 */
    const memoryToggle = wrapper.get('[aria-controls="memory-navigation"]')
    expect(jankToggle.attributes('aria-expanded')).toBe('false')
    expect(memoryToggle.attributes('aria-expanded')).toBe('false')
    expect(wrapper.get('#jank-navigation').isVisible()).toBe(false)
    expect(wrapper.get('#memory-navigation').isVisible()).toBe(false)
    await jankToggle.trigger('click')
    await memoryToggle.trigger('click')
    await flushPromises()
    expect(wrapper.get('#jank-navigation').isVisible()).toBe(true)
    expect(wrapper.get('#memory-navigation').isVisible()).toBe(true)
    expect(wrapper.get('#jank-navigation').text()).toContain('卡顿指标分析')
    expect(wrapper.get('#jank-navigation').text()).toContain('卡顿问题分析')
    expect(wrapper.get('#memory-navigation').text()).toContain('内存指标分析')
    expect(wrapper.get('#memory-navigation').text()).toContain('内存泄漏问题')
    await jankToggle.trigger('click')
    expect(jankToggle.attributes('aria-expanded')).toBe('false')
    expect(wrapper.get('#jank-navigation').isVisible()).toBe(false)
    expect(wrapper.get('#memory-navigation').isVisible()).toBe(true)
    expect(routerMock.push).not.toHaveBeenCalled()
  })

  it.each([
    ['jank-metrics', 'jank-navigation', '卡顿指标分析'],
    ['jank-issues', 'jank-navigation', '卡顿问题分析'],
    ['jank-issue-events', 'jank-navigation', '卡顿问题分析'],
    ['jank-event-detail', 'jank-navigation', '卡顿问题分析'],
    ['memory-metrics', 'memory-navigation', '内存指标分析'],
    ['memory-leaks', 'memory-navigation', '内存泄漏问题'],
  ])('直接进入 %s 时展开所属分组并选中子菜单', (routeName, groupId, label) => {
    routeMock.name = routeName
    mockedAppApi.list.mockResolvedValue(appFixtures)
    /** 当前路由对应的侧栏。 */
    const wrapper = mount(AppLayout, {
      props: { appId: 'mobile-apm' },
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })
    expect(wrapper.get(`[aria-controls="${groupId}"]`).attributes('aria-expanded')).toBe('true')
    expect(wrapper.get(`#${groupId}`).isVisible()).toBe(true)
    expect(wrapper.get(`#${groupId} .router-link-active`).text()).toBe(label)
  })

  it('路由变化后重新展开所属分组，没有应用时不显示分析分组', async () => {
    mockedAppApi.list.mockResolvedValue(appFixtures)
    /** 响应路由变化的侧栏。 */
    const wrapper = mount(AppLayout, {
      attachTo: document.body,
      props: { appId: 'mobile-apm' },
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })
    routeMock.name = 'jank-metrics'
    await flushPromises()
    expect(wrapper.get('#jank-navigation').isVisible()).toBe(true)
    await wrapper.get('[aria-controls="jank-navigation"]').trigger('click')
    expect(wrapper.get('#jank-navigation').isVisible()).toBe(false)
    routeMock.name = 'jank-event-detail'
    await flushPromises()
    expect(wrapper.get('#jank-navigation').isVisible()).toBe(true)
    routeMock.name = 'memory-leaks'
    await flushPromises()
    expect(wrapper.get('#memory-navigation').isVisible()).toBe(true)
    await wrapper.setProps({ appId: undefined })
    expect(wrapper.find('.nav-group-toggle').exists()).toBe(false)
  })
})
