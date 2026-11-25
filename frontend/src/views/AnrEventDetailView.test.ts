import { createPinia, setActivePinia } from 'pinia'
import { reactive } from 'vue'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { anrApi } from '../api/anrApi'
import type { AnrEventDetail } from '../types/anr'
import AnrEventDetailView from './AnrEventDetailView.vue'

/** 响应式详情路由。 */
const routeState = vi.hoisted(() => ({ params: { appId: 'app-1', eventId: 'event-1' }, query: {}, fullPath: '/apps/app-1/anrs/events/event-1' }))
const route = reactive(routeState)
vi.mock('vue-router', () => ({ useRoute: () => route, RouterLink: { template: '<a><slot /></a>' } }))
vi.mock('../api/anrApi', () => ({ anrApi: { event: vi.fn() } }))
enableAutoUnmount(afterEach)

/** 构造独立分区包含零值、缺失及部分还原的响应。 */
function detail(eventId = 'event-1'): AnrEventDetail {
  return {
    metadata: { eventId, occurredAt: '2026-11-20T00:00:00Z', receivedAt: '2026-11-20T00:00:01Z',
      packageName: 'com.example', appVersion: '1', versionCode: 1, buildId: 'b1', osVersion: '35', deviceModel: 'Pixel',
      environment: 'test', channel: 'test', sessionId: 'session', processId: 'process', anonymousDeviceId: 'device' },
    anr: { source: 'message_timeout', mainThreadStack: '<script>raw</script>', mainThreadStackTruncated: true,
      messageWhenMillis: 0, foreground: false, anrInfo: { status: 'unavailable', reason: 'not_found', truncated: false },
      trace: { status: 'partial', reason: 'size_limit', truncated: true, source: 'art_first_write', text: 'raw trace' },
      diagnostics: { memory: { status: 'ok', truncated: false, totalPssBytes: 0 },
        openFiles: { status: 'unavailable', reason: 'permission_denied', truncated: false, entries: [] } } },
    mainThreadJava: { status: 'symbolicated', text: 'restored.main()', symbolFileId: 'mapping-1', symbolFileRevision: 2 },
    traceJava: { status: 'partial', reason: 'output_limit', text: 'restored trace\n- locked', symbolFileId: 'mapping-1', symbolFileRevision: 2 },
  }
}

describe('ANR 详情', () => {
  beforeEach(() => {
    setActivePinia(createPinia()); vi.resetAllMocks()
    route.params.eventId = 'event-1'; route.fullPath = '/apps/app-1/anrs/events/event-1'
    vi.mocked(anrApi.event).mockResolvedValue(detail())
  })

  /** 原始文本安全呈现，并保留截断、缺失、权限失败和零值。 */
  it('展示原文、分区状态和零值', async () => {
    const wrapper = mount(AnrEventDetailView, { global: { stubs: { AppLayout: { template: '<div><slot /></div>' }, RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()
    expect(wrapper.text()).toContain('<script>raw</script>')
    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.text()).toContain('采集内容已截断')
    expect(wrapper.text()).toContain('系统未提供')
    expect(wrapper.text()).toContain('权限受限')
    expect(wrapper.text()).toContain('0 B')
    expect(wrapper.text()).toContain('0 ms')
    expect(wrapper.text()).toContain('首次命中写入')
    expect(wrapper.text()).not.toContain('崩溃线程')
  })

  /** 两个分区独立切换，事件变化后回到原文。 */
  it('独立切换还原视图并在事件变化后重置', async () => {
    const wrapper = mount(AnrEventDetailView, { global: { stubs: { AppLayout: { template: '<div><slot /></div>' }, RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()
    const panels = wrapper.findAll('.anr-stack')
    await panels[0].findAll('button')[1].trigger('click')
    await panels[1].findAll('button')[1].trigger('click')
    expect(panels[0].text()).toContain('restored.main()')
    expect(panels[1].text()).toContain('restored trace')
    expect(panels[1].text()).toContain('部分 Java 帧已还原')
    vi.mocked(anrApi.event).mockResolvedValue(detail('event-2'))
    route.params.eventId = 'event-2'; route.fullPath = '/apps/app-1/anrs/events/event-2'
    await flushPromises()
    expect(wrapper.findAll('.anr-stack')[0].text()).toContain('<script>raw</script>')
    expect(wrapper.findAll('.anr-stack')[0].text()).not.toContain('restored.main()')
  })

  /** 事件 ID 可复制并给出明确反馈。 */
  it('复制事件身份', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    const wrapper = mount(AnrEventDetailView, { global: { stubs: { AppLayout: { template: '<div><slot /></div>' }, RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()
    await wrapper.get('.page-header button').trigger('click')
    expect(writeText).toHaveBeenCalledWith('event-1')
    expect(wrapper.text()).toContain('已复制事件 ID')
  })
})
