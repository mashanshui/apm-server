import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, getActivePinia, setActivePinia } from 'pinia'
import AppSettingsView from './AppSettingsView.vue'
import { appApi } from '../api/appApi'
import { queryTokenApi } from '../api/queryTokenApi'

const routerMock = vi.hoisted(() => ({
  go: vi.fn(),
  push: vi.fn(),
  beforeLeave: undefined as undefined | (() => unknown),
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { appId: 'mobile-apm' }, query: {} }),
  useRouter: () => routerMock,
  onBeforeRouteLeave: (handler: () => unknown) => { routerMock.beforeLeave = handler },
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

vi.mock('../api/queryTokenApi', () => ({
  queryTokenApi: { list: vi.fn(), create: vi.fn(), revoke: vi.fn() },
}))

const mockedAppApi = vi.mocked(appApi)
const mockedQueryTokenApi = vi.mocked(queryTokenApi)

describe('AppSettingsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.resetAllMocks()
    mockedQueryTokenApi.list.mockResolvedValue({ items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 })
    routerMock.beforeLeave = undefined
    localStorage.clear()
    sessionStorage.clear()
  })

  it('loads editable metadata and shows save feedback', async () => {
    mockedAppApi.get.mockResolvedValue({
      appId: 'mobile-apm',
      name: '移动 APM',
      description: '首个应用',
      packageName: 'com.example.mobile',
      role: 'OWNER',
      createdAt: '2026-08-25T00:00:00Z',
      updatedAt: '2026-08-25T00:00:00Z',
    })
    mockedAppApi.update.mockResolvedValue({
      appId: 'mobile-apm',
      name: '移动 APM 生产',
      description: '已更新',
      packageName: 'com.example.mobile',
      role: 'OWNER',
      createdAt: '2026-08-25T00:00:00Z',
      updatedAt: '2026-08-25T00:01:00Z',
    })
    const wrapper = mount(AppSettingsView, {
      global: {
        stubs: {
          AppLayout: { template: '<div><slot /></div>' },
          RouterLink: { template: '<a><slot /></a>' },
        },
      },
    })
    await flushPromises()
    await wrapper.get('#settings-name').setValue('移动 APM 生产')
    await wrapper.get('#settings-description').setValue('已更新')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(mockedAppApi.update).toHaveBeenCalledWith('mobile-apm', {
      name: '移动 APM 生产',
      description: '已更新',
    })
    expect(wrapper.text()).toContain('应用设置已保存')
  })

  it('loads a key only on demand and supports hide and copy without storing it in the app', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    mockedAppApi.get.mockResolvedValue({
      appId: 'mobile-apm', name: '移动 APM', description: null,
      packageName: 'com.example.mobile', role: 'OWNER',
      createdAt: '2026-08-25T00:00:00Z', updatedAt: '2026-08-25T00:00:00Z',
    })
    mockedAppApi.getIngestCredential.mockResolvedValue({
      appId: 'mobile-apm', packageName: 'com.example.mobile', appKey: 'apm_ak_secret',
    })
    const wrapper = mount(AppSettingsView, {
      global: { stubs: { AppLayout: { template: '<div><slot /></div>' }, RouterLink: { template: '<a><slot /></a>' } } },
    })
    await flushPromises()

    expect(mockedAppApi.getIngestCredential).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('com.example.mobile')
    await wrapper.get('.credential-panel button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('apm_ak_secret')
    expect(JSON.stringify(getActivePinia()?.state.value)).not.toContain('apm_ak_secret')

    await wrapper.get('.credential-panel .button-primary').trigger('click')
    expect(writeText).toHaveBeenCalledWith('apm_ak_secret')
    await wrapper.get('.credential-panel button').trigger('click')
    expect(wrapper.text()).not.toContain('apm_ak_secret')
  })

  it('does not expose credential controls to developer role', async () => {
    mockedAppApi.get.mockResolvedValue({
      appId: 'mobile-apm', name: '移动 APM', description: null,
      packageName: 'com.example.mobile', role: 'DEVELOPER',
      createdAt: '2026-08-25T00:00:00Z', updatedAt: '2026-08-25T00:00:00Z',
    })
    const wrapper = mount(AppSettingsView, {
      global: { stubs: { AppLayout: { template: '<div><slot /></div>' }, RouterLink: { template: '<a><slot /></a>' } } },
    })
    await flushPromises()
    expect(wrapper.find('.credential-panel').exists()).toBe(false)
    expect(mockedAppApi.getIngestCredential).not.toHaveBeenCalled()
  })

  it('does not expose credential controls to viewer role', async () => {
    mockedAppApi.get.mockResolvedValue({
      appId: 'mobile-apm', name: '移动 APM', description: null,
      packageName: 'com.example.mobile', role: 'VIEWER',
      createdAt: '2026-08-25T00:00:00Z', updatedAt: '2026-08-25T00:00:00Z',
    })
    const wrapper = mount(AppSettingsView, {
      global: { stubs: { AppLayout: { template: '<div><slot /></div>' }, RouterLink: { template: '<a><slot /></a>' } } },
    })
    await flushPromises()
    expect(wrapper.find('.credential-panel').exists()).toBe(false)
    expect(wrapper.text()).toContain('com.example.mobile')
  })

  it('clears an exposed key on session expiry and never writes it to browser persistence or URL', async () => {
    mockedAppApi.get.mockResolvedValue({
      appId: 'mobile-apm', name: '移动 APM', description: null,
      packageName: 'com.example.mobile', role: 'ADMIN',
      createdAt: '2026-08-25T00:00:00Z', updatedAt: '2026-08-25T00:00:00Z',
    })
    mockedAppApi.getIngestCredential.mockResolvedValue({
      appId: 'mobile-apm', packageName: 'com.example.mobile', appKey: 'apm_ak_session_secret',
    })
    const wrapper = mount(AppSettingsView, {
      global: { stubs: { AppLayout: { template: '<div><slot /></div>' }, RouterLink: { template: '<a><slot /></a>' } } },
    })
    await flushPromises()
    await wrapper.get('.credential-panel button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('apm_ak_session_secret')

    window.dispatchEvent(new CustomEvent('apm:auth-expired'))
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).not.toContain('apm_ak_session_secret')
    expect(JSON.stringify(localStorage)).not.toContain('apm_ak_session_secret')
    expect(JSON.stringify(sessionStorage)).not.toContain('apm_ak_session_secret')
    expect(window.location.href).not.toContain('apm_ak_session_secret')
  })

  it('keeps credential state empty when the credential request fails', async () => {
    mockedAppApi.get.mockResolvedValue({
      appId: 'mobile-apm', name: '移动 APM', description: null,
      packageName: 'com.example.mobile', role: 'OWNER',
      createdAt: '2026-08-25T00:00:00Z', updatedAt: '2026-08-25T00:00:00Z',
    })
    mockedAppApi.getIngestCredential.mockRejectedValue(new Error('credential unavailable'))
    const wrapper = mount(AppSettingsView, {
      global: { stubs: { AppLayout: { template: '<div><slot /></div>' }, RouterLink: { template: '<a><slot /></a>' } } },
    })
    await flushPromises()
    await wrapper.get('.credential-panel button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).not.toContain('apm_ak_')
    expect(wrapper.text()).toContain('请求失败，请稍后重试')
    expect(JSON.stringify(getActivePinia()?.state.value)).not.toContain('apm_ak_')
  })

  it('clears an exposed key when leaving the current app route', async () => {
    mockedAppApi.get.mockResolvedValue({
      appId: 'mobile-apm', name: '移动 APM', description: null,
      packageName: 'com.example.mobile', role: 'OWNER',
      createdAt: '2026-08-25T00:00:00Z', updatedAt: '2026-08-25T00:00:00Z',
    })
    mockedAppApi.getIngestCredential.mockResolvedValue({
      appId: 'mobile-apm', packageName: 'com.example.mobile', appKey: 'apm_ak_route_secret',
    })
    const wrapper = mount(AppSettingsView, {
      global: { stubs: { AppLayout: { template: '<div><slot /></div>' }, RouterLink: { template: '<a><slot /></a>' } } },
    })
    await flushPromises()
    await wrapper.get('.credential-panel button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('apm_ak_route_secret')

    expect(routerMock.beforeLeave?.()).toBe(true)
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).not.toContain('apm_ak_route_secret')
  })
})
