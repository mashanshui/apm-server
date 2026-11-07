import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import CrashEventDetailView from './CrashEventDetailView.vue'
import { useAppStore } from '../stores/apps'
import { crashApi, requireCurrentCrash } from '../api/crashApi'

const routeMock = reactive({
  params: { appId: 'app-1', eventId: 'event-1' },
  query: {},
  fullPath: '/apps/app-1/crashes/events/event-1',
})

vi.mock('vue-router', () => ({
  useRoute: () => routeMock,
  RouterLink: { template: '<a><slot /></a>' },
}))

vi.mock('../api/crashApi', async importOriginal => ({
  ...await importOriginal<typeof import('../api/crashApi')>(),
  crashApi: { event: vi.fn() },
  errorMessage: (error: unknown) => error instanceof Error ? error.message : '请求失败',
  isAbortError: () => false,
}))

vi.mock('../components/CrashAnalysisPanel.vue', () => ({ default: { props: ['canCreate', 'canManage'], template: '<section class="analysis-stub" :data-create="canCreate" :data-manage="canManage" />' } }))
vi.mock('../components/StackTrace.vue', () => ({ default: { template: '<div class="raw-stack">原始堆栈</div>' } }))
vi.mock('../components/EventIdentityFields.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../components/StatusMessage.vue', () => ({ default: { props: ['message'], template: '<div>{{ message }}</div>' } }))

const mockedCrashApi = vi.mocked(crashApi)

/** 构造详情响应，便于覆盖实时还原和缺少 mapping 的页面状态。 */
function eventResponse(overrides: Partial<Awaited<ReturnType<typeof crashApi.event>>> = {}) {
  return {
    appId: 'app-1', eventId: 'event-1', packageName: 'com.example.app',
    occurredAt: '2026-09-19T00:00:00Z', receivedAt: '2026-09-19T00:00:01Z',
    sessionId: null, processId: null, anonymousDeviceId: null, appVersion: '1.0', versionCode: 1,
    buildId: 'release-1', channel: 'prod', environment: 'release', osVersion: '35', deviceModel: 'demo',
    networkType: null, exceptionType: 'java.lang.IllegalStateException', fingerprint: 'fingerprint',
    fingerprintVersion: 'resolved-v1', symbolicationStatus: 'symbolicated' as const,
    groupingKind: 'resolved' as const, crashKind: 'jvm', fatal: true, symbolFileSha256: 'a'.repeat(64), processedAt: '2026-11-05T00:00:00Z',
    stack: { format: 'retrace_text' as const, content: 'com.example.RealClass.realMethod(RealClass.java:42)' },
    symbolFileId: 'symbol-1', symbolFileRevision: 2, symbolicationReason: null,
    ...overrides,
  }
}

describe('CrashEventDetailView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
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
    expect(wrapper.find('.segmented-control').exists()).toBe(false)
    expect(wrapper.find('.raw-stack').exists()).toBe(false)
    expect(wrapper.find('.analysis-stub').exists()).toBe(true)
    wrapper.unmount()
  })

  /** 未还原时保留原始内容和原因，并安全渲染服务端返回的文本。 */
  it('keeps raw fallback and escapes retraced text', async () => {
    mockedCrashApi.event.mockResolvedValue(eventResponse({
      symbolicationStatus: 'pending', groupingKind: 'temporary',
      stack: { format: 'raw_chain', content: { kind: 'jvm', fatal: true, throwableChain: [] } },
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
      stack: { format: 'retrace_text', content: '<script>alert(1)</script>\n候选二' },
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
    rawWrapper.unmount(); escapedWrapper.unmount()
  })

  /** 直接进入详情时 currentApp 未加载，角色来自应用列表且不能串应用。 */
  it('uses the current route app membership when opening detail directly', async () => {
    const store = useAppStore()
    store.apps = [{ appId: 'app-1', role: 'DEVELOPER' } as never]
    const wrapper = mount(CrashEventDetailView, { global: { stubs: { AppLayout: { template: '<div><slot /></div>' }, RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()
    expect(wrapper.get('.analysis-stub').attributes('data-create')).toBe('true')
    expect(wrapper.get('.analysis-stub').attributes('data-manage')).toBe('false')
    store.apps = [{ appId: 'other-app', role: 'OWNER' } as never]
    await flushPromises()
    expect(wrapper.get('.analysis-stub').attributes('data-create')).toBe('false')
    wrapper.unmount()
  })

  /** 路由刷新后只保留最新单份材料，failed 仍可分析和由管理员管理文件。 */
  it('moves from failed raw chain to a fixed result after refresh', async () => {
    useAppStore().apps = [{appId:'app-1',role:'OWNER'} as never]
    mockedCrashApi.event.mockResolvedValue(eventResponse({symbolicationStatus:'failed',groupingKind:'temporary',symbolicationReason:'output_limit',
      stack:{format:'raw_chain',content:{kind:'jvm',fatal:true,throwableChain:[]}}}))
    const wrapper=mount(CrashEventDetailView,{global:{stubs:{AppLayout:{template:'<div><slot /></div>'},RouterLink:{template:'<a><slot /></a>'}}}})
    await flushPromises()
    expect(wrapper.text()).toContain('还原失败')
    expect(wrapper.find('.raw-stack').exists()).toBe(true)
    expect(wrapper.text()).toContain('上传此 buildId 的 mapping')
    mockedCrashApi.event.mockResolvedValue(eventResponse())
    routeMock.fullPath+='?refresh=current'
    await flushPromises()
    expect(wrapper.find('.raw-stack').exists()).toBe(false)
    expect(wrapper.text()).toContain('首次成功结果已固定')
    expect(wrapper.find('.analysis-stub').exists()).toBe(true)
    wrapper.unmount()
  })

  /** 旧详情不会用空链或猜测字段降级展示。 */
  it('shows an explicit protocol error for an old backend detail', async () => {
    mockedCrashApi.event.mockImplementation(async()=>requireCurrentCrash({rawCrash:{},symbolicationStatus:'raw_only'} as never))
    const wrapper=mount(CrashEventDetailView,{global:{stubs:{AppLayout:{template:'<div><slot /></div>'},RouterLink:{template:'<a><slot /></a>'}}}})
    await flushPromises()
    expect(wrapper.text()).toContain('协议错误')
    expect(wrapper.find('.raw-stack').exists()).toBe(false)
    expect(wrapper.find('.analysis-stub').exists()).toBe(false)
    wrapper.unmount()
  })

})
