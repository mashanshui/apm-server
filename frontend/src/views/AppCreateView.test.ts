import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import AppCreateView from './AppCreateView.vue'
import { appApi } from '../api/appApi'
import { ApiError } from '../api/http'

const routerMock = vi.hoisted(() => ({ push: vi.fn() }))

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {}, params: {} }),
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
const appId = '550e8400-e29b-41d4-a716-446655440000'

describe('AppCreateView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.resetAllMocks()
  })

  it('uses the package name as the default display name when optional fields are empty', async () => {
    mockedAppApi.create.mockResolvedValue({
      appId,
      name: 'com.example.mobile',
      description: null,
      packageName: 'com.example.mobile',
      role: 'OWNER',
      createdAt: '2026-08-25T00:00:00Z',
      updatedAt: '2026-08-25T00:00:00Z',
    })
    const wrapper = mount(AppCreateView, {
      global: {
        stubs: {
          AppLayout: { template: '<div><slot /></div>' },
          RouterLink: { template: '<a><slot /></a>' },
        },
      },
    })

    await wrapper.get('#app-package-name').setValue('com.example.mobile')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(mockedAppApi.create).toHaveBeenCalledWith({ packageName: 'com.example.mobile' })
    expect(routerMock.push).toHaveBeenCalledWith({
      name: 'app-settings',
      params: { appId },
      query: { created: '1' },
    })
  })

  it('submits the optional name and description together with the required package name', async () => {
    mockedAppApi.create.mockResolvedValue({
      appId,
      name: '线上 Demo',
      description: '用于线上验证',
      packageName: 'com.example.mobile',
      role: 'OWNER',
      createdAt: '2026-08-25T00:00:00Z',
      updatedAt: '2026-08-25T00:00:00Z',
    })
    const wrapper = mount(AppCreateView, {
      global: {
        stubs: {
          AppLayout: { template: '<div><slot /></div>' },
          RouterLink: { template: '<a><slot /></a>' },
        },
      },
    })

    await wrapper.get('#app-name').setValue('  线上 Demo  ')
    await wrapper.get('#app-description').setValue('  用于线上验证  ')
    await wrapper.get('#app-package-name').setValue('com.example.mobile')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(mockedAppApi.create).toHaveBeenCalledWith({
      name: '线上 Demo',
      description: '用于线上验证',
      packageName: 'com.example.mobile',
    })
  })

  it('validates optional name and description lengths before creating an app', async () => {
    const wrapper = mount(AppCreateView, {
      global: {
        stubs: {
          AppLayout: { template: '<div><slot /></div>' },
          RouterLink: { template: '<a><slot /></a>' },
        },
      },
    })

    await wrapper.get('#app-name').setValue('x'.repeat(101))
    await wrapper.get('#app-description').setValue('y'.repeat(501))
    await wrapper.get('#app-package-name').setValue('com.example.mobile')
    await wrapper.get('form').trigger('submit')

    expect(wrapper.text()).toContain('应用名称不能超过 100 个字符')
    expect(wrapper.text()).toContain('应用描述不能超过 500 个字符')
    expect(mockedAppApi.create).not.toHaveBeenCalled()
  })

  it('rejects uppercase, single-segment and invalid package names without creating an app', async () => {
    const wrapper = mount(AppCreateView, {
      global: {
        stubs: {
          AppLayout: { template: '<div><slot /></div>' },
          RouterLink: { template: '<a><slot /></a>' },
        },
      },
    })

    await wrapper.get('#app-package-name').setValue('Com.example.app')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.text()).toContain('请输入全小写且唯一的 Android 包名')

    await wrapper.get('#app-package-name').setValue('invalid')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.text()).toContain('请输入全小写且唯一的 Android 包名')
    expect(mockedAppApi.create).not.toHaveBeenCalled()
  })

  it('shows a package conflict as a field error', async () => {
    mockedAppApi.create.mockRejectedValue(new ApiError({
      status: 409,
      code: 'PACKAGE_NAME_CONFLICT',
      message: '包名已存在',
      retryable: false,
    }))
    const wrapper = mount(AppCreateView, {
      global: {
        stubs: {
          AppLayout: { template: '<div><slot /></div>' },
          RouterLink: { template: '<a><slot /></a>' },
        },
      },
    })

    await wrapper.get('#app-package-name').setValue('com.example.mobile')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('这个包名已经被使用')
  })
})
