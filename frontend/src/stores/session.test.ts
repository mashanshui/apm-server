import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '../api/http'
import { authApi } from '../api/authApi'
import { useSessionStore } from './session'

vi.mock('../api/authApi', () => ({
  authApi: {
    session: vi.fn(),
    login: vi.fn(),
    logout: vi.fn(),
  },
}))

const mockedAuthApi = vi.mocked(authApi)

describe('session store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.resetAllMocks()
  })

  it('treats an expired session as anonymous without retaining stale user data', async () => {
    mockedAuthApi.session.mockRejectedValue(new ApiError({
      status: 401,
      code: 'AUTH_REQUIRED',
      message: '请先登录',
    }))
    const store = useSessionStore()

    await store.initialize()

    expect(store.initialized).toBe(true)
    expect(store.isAuthenticated).toBe(false)
    expect(store.error).toBeNull()
  })

  it('stores only the returned user after login and clears it on expiration', async () => {
    mockedAuthApi.login.mockResolvedValue({
      id: 'user-1',
      email: 'test@example.com',
      displayName: '测试用户',
    })
    const store = useSessionStore()

    await store.login('test@example.com', 'secret')
    expect(store.user?.email).toBe('test@example.com')
    expect(store.isAuthenticated).toBe(true)

    store.handleExpired()
    expect(store.user).toBeNull()
    expect(store.expired).toBe(true)
  })
})
