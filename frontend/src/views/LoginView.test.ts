import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import LoginView from './LoginView.vue'
import { authApi } from '../api/authApi'

const routerMock = vi.hoisted(() => ({ push: vi.fn() }))

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: { expired: '1' } }),
  useRouter: () => routerMock,
  RouterLink: { template: '<a><slot /></a>' },
}))

vi.mock('../api/authApi', () => ({
  authApi: {
    session: vi.fn(),
    login: vi.fn(),
    logout: vi.fn(),
  },
}))

const mockedAuthApi = vi.mocked(authApi)

describe('LoginView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.resetAllMocks()
  })

  it('shows expiration feedback and returns to a safe app path after login', async () => {
    mockedAuthApi.login.mockResolvedValue({
      id: 'user-1',
      email: 'test@example.com',
      displayName: '测试用户',
    })
    const wrapper = mount(LoginView, {
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })

    expect(wrapper.text()).toContain('登录状态已过期')
    await wrapper.get('#email').setValue('test@example.com')
    await wrapper.get('#password').setValue('secret')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(mockedAuthApi.login).toHaveBeenCalledWith({ email: 'test@example.com', password: 'secret' })
    expect(routerMock.push).toHaveBeenCalledWith('/apps')
  })

  it('keeps the form local when required credentials are missing', async () => {
    const wrapper = mount(LoginView, {
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })

    await wrapper.get('form').trigger('submit')

    expect(wrapper.text()).toContain('请输入邮箱和密码')
    expect(mockedAuthApi.login).not.toHaveBeenCalled()
  })
})
