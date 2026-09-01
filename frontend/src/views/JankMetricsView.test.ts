import { ref } from 'vue'
import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import JankMetricsView from './JankMetricsView.vue'

const routerMock = vi.hoisted(() => ({ push: vi.fn() }))
const routeMock = vi.hoisted(() => ({ params: { appId: 'demo' }, query: {}, fullPath: '/apps/demo/jank-metrics' }))
const composableMock = vi.hoisted(() => ({ use: vi.fn() }))
vi.mock('vue-router', () => ({ useRoute: () => routeMock, useRouter: () => routerMock, RouterLink: { template: '<a><slot /></a>' } }))
vi.mock('../composables/useJankMetrics', () => ({ useJankMetrics: composableMock.use }))

function state() {
  return {
    summary: ref({ appId: 'demo', from: '', to: '', status: 'no_valid_data', dataSource: 'memory', metrics: [] }), summaryLoading: ref(false), summaryError: ref(null),
    trend: ref(null), trendLoading: ref(false), trendError: ref('趋势查询失败'),
    dimensions: ref({ appId: 'demo', from: '', to: '', metric: 'fps', dimension: 'scene', points: [], status: 'no_data', dataSource: 'memory' }), dimensionsLoading: ref(false), dimensionsError: ref(null),
    loading: ref(false), load: vi.fn(), loadSummary: vi.fn(), loadTrend: vi.fn(), loadDimensions: vi.fn(), cancel: vi.fn(),
  }
}

describe('JankMetricsView', () => {
  beforeEach(() => { vi.resetAllMocks(); composableMock.use.mockReturnValue(state()) })

  it('保留成功区域并在切换挂起率时写入 UTC day 与兼容维度', async () => {
    const wrapper = mount(JankMetricsView, { global: { stubs: {
      AppLayout: { template: '<div><slot /></div>' }, JankMetricFilterBar: { template: '<div />' },
      JankMetricTrendChart: { template: '<div />' }, RouterLink: { template: '<a><slot /></a>' },
    } } })
    expect(wrapper.text()).toContain('趋势查询失败')
    expect(wrapper.text()).toContain('没有有效 FPS 记录')
    const tabs = wrapper.findAll('.metric-tabs button')
    await tabs[1].trigger('click')
    expect(routerMock.push).toHaveBeenCalledWith(expect.objectContaining({
      name: 'jank-metrics', params: { appId: 'demo' },
      query: expect.objectContaining({ metric: 'suspension_rate', interval: 'day', dimension: 'deviceModel' }),
    }))
    const call = routerMock.push.mock.calls[0][0]
    expect(call.query.scene).toBeUndefined()
  })
})
