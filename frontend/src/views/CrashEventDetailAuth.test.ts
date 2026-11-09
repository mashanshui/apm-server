import { describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import { crashApi } from '../api/crashApi'
import router from '../router'
import { pinia } from '../stores/pinia'
import { useSessionStore } from '../stores/session'
import type { CrashEventDetailResponse } from '../types/crash'

// 使用真实入口、路由与 Session；只隔离网络和无关页面组件。
vi.mock('../api/crashApi', async importOriginal => ({
  ...await importOriginal<typeof import('../api/crashApi')>(),
  crashApi: { event: vi.fn() },
}))
vi.mock('../components/AppLayout.vue', () => ({ default: { template: '<div><slot /></div>' } }))
vi.mock('../components/CrashAnalysisPanel.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../components/EventIdentityFields.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../components/StackTrace.vue', () => ({ default: { template: '<div />' } }))

/** 模拟已授权请求迟到时返回的合成诊断，不能在会话失效后展示。 */
const staleDetail: CrashEventDetailResponse = {
  appId: 'app-1', eventId: 'event-1', packageName: 'com.example.app',
  occurredAt: '2026-11-09T00:00:00Z', receivedAt: '2026-11-09T00:00:00Z',
  sessionId: null, processId: null, anonymousDeviceId: null,
  appVersion: '1', versionCode: 1, buildId: 'test', channel: 'test', environment: 'test',
  osVersion: '35', deviceModel: 'test', networkType: null, exceptionType: 'synthetic-late-crash',
  fingerprint: 'fingerprint', fingerprintVersion: 'tmp-v1', symbolicationStatus: 'pending',
  groupingKind: 'temporary', crashKind: 'jvm', fatal: true, symbolFileId: null,
  symbolFileRevision: null, symbolFileSha256: null, processedAt: null, symbolicationReason: 'mapping_missing',
  stack: { format: 'raw_chain', content: { kind: 'jvm', fatal: true, throwableChain: [] } },
  diagnostics: { openFiles: { status: 'ok', truncated: false, count: 1, entries: [{ fd: 3, target: '[path]' }] } },
}

describe('Crash 详情会话失效集成', () => {
  /** 登录跳转延迟时，入口收到 401 后仍要立即清除并隔离旧详情。 */
  it('rejects a late authorized response while login navigation is delayed', async () => {
    document.body.innerHTML = '<div id="app"></div>'
    /** 使用真实 Session Store 模拟当前已登录用户。 */
    const session = useSessionStore(pinia)
    session.user = { id: 'user-1', email: 'test@example.invalid', displayName: '测试用户' }
    session.initialized = true
    /** 控制旧请求在 Session 失效后才返回。 */
    let completeOld!: (value: CrashEventDetailResponse) => void
    /** 记录页面传递给网络层的取消信号。 */
    let signal: AbortSignal | undefined
    vi.mocked(crashApi.event).mockImplementation((_appId, _eventId, requestSignal) => {
      signal = requestSignal
      return new Promise(resolve => { completeOld = resolve })
    })
    /** 暂停登录跳转以验证组件卸载前的窗口。 */
    let releaseLogin!: () => void
    /** 登录跳转受控门禁。 */
    const navigationGate = new Promise<void>(resolve => { releaseLogin = resolve })
    /** 测试结束后撤销新增的路由守卫。 */
    const removeGuard = router.beforeEach(async to => { if (to.name === 'login') await navigationGate })

    try {
      await router.push('/apps/app-1/crashes/events/event-1')
      await router.isReady()
      await import('../main')
      await nextTick()
      expect(typeof completeOld).toBe('function')
      expect(signal?.aborted).toBe(false)

      window.dispatchEvent(new CustomEvent('apm:auth-expired'))
      expect(session.user).toBeNull()
      expect(signal?.aborted).toBe(true)
      completeOld(staleDetail)
      await flushPromises()
      expect(router.currentRoute.value.name).toBe('crash-event-detail')
      expect(document.body.textContent).toContain('登录状态已失效')
      expect(document.body.textContent).not.toContain('synthetic-late-crash')
      expect(document.body.textContent).not.toContain('文件描述符')

      releaseLogin()
      await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('login'), { timeout: 3000 })
      await nextTick()
      expect(document.body.textContent).not.toContain('synthetic-late-crash')
    } finally {
      releaseLogin()
      removeGuard()
      document.body.innerHTML = ''
    }
  })
})
