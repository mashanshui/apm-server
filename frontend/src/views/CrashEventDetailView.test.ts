import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import CrashEventDetailView from './CrashEventDetailView.vue'
import { crashApi } from '../api/crashApi'

const routeMock = {
  params: { appId: 'app-1', eventId: 'event-1' },
  query: {},
  fullPath: '/apps/app-1/crashes/events/event-1',
}

vi.mock('vue-router', () => ({
  useRoute: () => routeMock,
  RouterLink: { template: '<a><slot /></a>' },
}))

vi.mock('../api/crashApi', () => ({
  crashApi: { event: vi.fn() },
  errorMessage: (error: unknown) => error instanceof Error ? error.message : '请求失败',
  isAbortError: () => false,
}))

vi.mock('../components/StackTrace.vue', () => ({ default: { template: '<div class="raw-stack">原始堆栈</div>' } }))
vi.mock('../components/EventIdentityFields.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../components/StatusMessage.vue', () => ({ default: { template: '<div><slot /></div>' } }))

const mockedCrashApi = vi.mocked(crashApi)

/** 构造详情响应，便于覆盖实时还原和缺少 mapping 的页面状态。 */
function eventResponse(overrides: Partial<Awaited<ReturnType<typeof crashApi.event>>> = {}) {
  return {
    appId: 'app-1', eventId: 'event-1', packageName: 'com.example.app',
    occurredAt: '2026-09-19T00:00:00Z', receivedAt: '2026-09-19T00:00:01Z',
    sessionId: null, processId: null, anonymousDeviceId: null, appVersion: '1.0', versionCode: 1,
    buildId: 'release-1', channel: 'prod', environment: 'release', osVersion: '35', deviceModel: 'demo',
    networkType: null, exceptionType: 'java.lang.IllegalStateException', fingerprint: 'fingerprint',
    fingerprintVersion: 'v1', symbolicationStatus: 'symbolicated' as const,
    symbolicatedStackText: 'com.example.RealClass.realMethod(RealClass.java:42)',
    symbolFileId: 'symbol-1', symbolFileRevision: 2, symbolicationReason: null,
    rawCrash: { kind: 'jvm', fatal: true, throwableChain: [] },
    ...overrides,
  }
}

describe('CrashEventDetailView', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    routeMock.fullPath = '/apps/app-1/crashes/events/event-1'
    mockedCrashApi.event.mockResolvedValue(eventResponse())
  })

  /** 详情使用本次实时还原结果，并能切换回结构化原始堆栈。 */
  it('shows live symbolication and preserves the raw toggle', async () => {
    const wrapper = mount(CrashEventDetailView, { global: { stubs: {
      AppLayout: { template: '<div><slot /></div>' },
      RouterLink: { template: '<a><slot /></a>' },
    } } })
    await flushPromises()

    expect(wrapper.text()).toContain('com.example.RealClass.realMethod')
    expect(wrapper.text()).toContain('revision 2')
    await wrapper.get('.segmented-control button').trigger('click')
    expect(wrapper.find('.raw-stack').exists()).toBe(true)
    expect(wrapper.text()).toContain('上传此 buildId 的 mapping')
  })

  /** 未还原时保留原始内容和原因，并安全渲染服务端返回的文本。 */
  it('keeps raw fallback and escapes retraced text', async () => {
    mockedCrashApi.event.mockResolvedValue(eventResponse({
      symbolicationStatus: 'raw_only',
      symbolicatedStackText: null,
      symbolFileId: null,
      symbolFileRevision: null,
      symbolicationReason: 'mapping_missing',
    }))
    const rawWrapper = mount(CrashEventDetailView, { global: { stubs: {
      AppLayout: { template: '<div><slot /></div>' },
      RouterLink: { template: '<a><slot /></a>' },
    } } })
    await flushPromises()
    expect(rawWrapper.text()).toContain('当前 buildId 没有可用 mapping')
    expect(rawWrapper.find('.raw-stack').exists()).toBe(true)
    expect(rawWrapper.find('.symbolicated-panel').exists()).toBe(false)

    mockedCrashApi.event.mockResolvedValue(eventResponse({
      symbolicatedStackText: '<script>alert(1)</script>\n候选二',
    }))
    routeMock.fullPath = '/apps/app-1/crashes/events/event-1?refresh=2'
    const escapedWrapper = mount(CrashEventDetailView, { global: { stubs: {
      AppLayout: { template: '<div><slot /></div>' },
      RouterLink: { template: '<a><slot /></a>' },
    } } })
    await flushPromises()
    expect(escapedWrapper.find('pre').text()).toContain('<script>alert(1)</script>')
    expect(escapedWrapper.find('pre').element.innerHTML).not.toContain('<script>alert(1)</script>')
    expect(escapedWrapper.text()).toContain('候选二')
  })
})
