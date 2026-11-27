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

  /** 分区按钮只展示对应卡片，事件切换后回到主线程 Java 栈。 */
  it('通过按钮切换分区并在事件变化后重置', async () => {
    const wrapper = mount(AnrEventDetailView, { global: { stubs: { AppLayout: { template: '<div><slot /></div>' }, RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()
    const tabs = wrapper.findAll('.detail-tabs button')
    expect(tabs.map(tab => tab.text())).toEqual(['主线程 Java 栈', '系统 trace', 'ANR_INFO', '日志', '内存快照', 'FD 信息', '进程信息'])
    expect(tabs[0].attributes('aria-selected')).toBe('true')
    // jsdom 的计算样式存在缓存，v-show 显隐直接断言内联 display。
    const displayOf = (selector: string) => (wrapper.find(selector).element as HTMLElement).style.display
    const stackDisplay = (index: number) => (wrapper.findAll('.anr-stack')[index].element as HTMLElement).style.display
    expect(displayOf('.anr-stack')).toBe('')
    expect(stackDisplay(1)).toBe('none')
    await tabs[1].trigger('click')
    expect(stackDisplay(0)).toBe('none')
    expect(stackDisplay(1)).toBe('')
    await tabs[4].trigger('click')
    expect(displayOf('[aria-label="内存快照"]')).toBe('')
    expect(stackDisplay(1)).toBe('none')
    vi.mocked(anrApi.event).mockResolvedValue(detail('event-2'))
    route.params.eventId = 'event-2'; route.fullPath = '/apps/app-1/anrs/events/event-2'
    await flushPromises()
    expect(wrapper.findAll('.detail-tabs button')[0].attributes('aria-selected')).toBe('true')
    expect(stackDisplay(0)).toBe('')
  })

  /** 每个 ANR 分区的标签和面板双向关联，方向键切换真实选中项与焦点。 */
  it('通过键盘切换关联的详情面板', async () => {
    /** 挂入文档后可验证活动元素。 */
    const wrapper = mount(AnrEventDetailView, { attachTo: document.body, global: { stubs: {
      AppLayout: { template: '<div><slot /></div>' }, RouterLink: { template: '<a><slot /></a>' },
    } } })
    await flushPromises()
    /** 按页面顺序读取所有详情标签。 */
    const tabs = wrapper.findAll('.detail-tabs [role="tab"]')
    for (const tab of tabs) {
      /** 标签所控制的面板标识。 */
      const panelId = tab.attributes('aria-controls')
      expect(panelId).toBeTruthy()
      /** 面板须反向引用标签并可通过键盘进入。 */
      const panel = wrapper.get(`#${panelId}`)
      expect(panel.attributes('role')).toBe('tabpanel')
      expect(panel.attributes('aria-labelledby')).toBe(tab.attributes('id'))
      expect(panel.attributes('tabindex')).toBe('0')
    }
    ;(tabs[0].element as HTMLButtonElement).focus()
    await tabs[0].trigger('keydown', { key: 'ArrowRight' })
    expect(document.activeElement).toBe(tabs[1].element)
    expect(tabs[1].attributes('aria-selected')).toBe('true')
    expect((wrapper.get('#anr-detail-panel-trace').element as HTMLElement).style.display).toBe('')
    expect((wrapper.get('#anr-detail-panel-main').element as HTMLElement).style.display).toBe('none')
    wrapper.unmount()
  })
})
