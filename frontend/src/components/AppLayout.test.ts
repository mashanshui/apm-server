import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import AppLayout from './AppLayout.vue'
import { appApi } from '../api/appApi'
import { useSessionStore } from '../stores/session'

const routerMock = vi.hoisted(() => ({ push: vi.fn() }))
const routeMock = vi.hoisted(() => ({ name: 'crash-overview' as string, query: {} as Record<string, string> }))

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
})
