import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import AppListView from './AppListView.vue'
import { appApi } from '../api/appApi'

const routerMock = vi.hoisted(() => ({ push: vi.fn() }))

vi.mock('vue-router', () => ({
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

describe('AppListView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.resetAllMocks()
  })

  it('loads accessible apps and exposes the analysis action', async () => {
    mockedAppApi.list.mockResolvedValue([{
      appId: 'mobile-apm',
      name: '移动 APM',
      description: '首个应用',
      packageName: 'com.example.mobile',
      role: 'OWNER',
      createdAt: '2026-08-25T00:00:00Z',
      updatedAt: '2026-08-25T00:00:00Z',
    }])
    const wrapper = mount(AppListView, {
      global: {
        stubs: {
          AppLayout: { template: '<div><slot /></div>' },
          RouterLink: { template: '<a><slot /></a>' },
        },
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('移动 APM')
    expect(wrapper.get('.app-name-button strong').text()).toBe('移动 APM')
    expect(wrapper.get('.app-id').text()).toBe('com.example.mobile')
    await wrapper.get('.app-name-button').trigger('click')
    expect(routerMock.push).toHaveBeenCalledWith({ name: 'crash-overview', params: { appId: 'mobile-apm' } })
  })
})
