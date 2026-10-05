import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { appApi } from './appApi'
import { useSessionStore } from '../stores/session'

/** 使用真实 API 客户端与 Store，模拟浏览器在登录响应时接收新 Cookie。 */
describe('登录后的 CSRF 衔接', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    setActivePinia(createPinia())
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/'
    localStorage.clear()
    sessionStorage.clear()
  })

  /** 首个编辑请求必须读取登录响应的新 Cookie，不依赖缓存 Token。 */
  it('登录后立即修改应用时使用新 Cookie，且不持久化凭据', async () => {
    // 登录前后的 Token 只属于测试 Cookie，不进入应用状态。
    document.cookie = 'XSRF-TOKEN=before-login; Path=/'
    // 捕获两次真实客户端调用的请求头。
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (_url, options) => {
      if (options?.method === 'POST') {
        document.cookie = 'XSRF-TOKEN=after-login; Path=/'
        return new Response(JSON.stringify({ id: 'user-1', email: 'test@example.com', displayName: '用户' }))
      }
      return new Response(JSON.stringify({ appId: 'app-1', name: '新名称', description: '描述' }))
    })
    // Store 与生产一致，只保存响应的用户信息。
    const store = useSessionStore()
    await store.login('test@example.com', 'test-only-password')
    await appApi.update('app-1', { name: '新名称', description: '描述' })

    expect(new Headers(fetchMock.mock.calls[0][1]?.headers).get('X-XSRF-TOKEN')).toBe('before-login')
    expect(new Headers(fetchMock.mock.calls[1][1]?.headers).get('X-XSRF-TOKEN')).toBe('after-login')
    expect(fetchMock.mock.calls.every(([, options]) => options?.credentials === 'include')).toBe(true)
    expect(store.user).toEqual({ id: 'user-1', email: 'test@example.com', displayName: '用户' })
    expect(JSON.stringify(store.$state)).not.toMatch(/password|XSRF|before-login|after-login/)
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })
})
