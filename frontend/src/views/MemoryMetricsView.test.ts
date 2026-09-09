import { ref } from 'vue'
import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import MemoryMetricsView from './MemoryMetricsView.vue'

const routerMock = vi.hoisted(() => ({ push: vi.fn() }))
const routeMock = vi.hoisted(() => ({ params: { appId: 'demo' }, query: {}, fullPath: '/apps/demo/memory-metrics' }))
const composableMock = vi.hoisted(() => ({ use: vi.fn() }))
vi.mock('vue-router', () => ({ useRoute: () => routeMock, useRouter: () => routerMock }))
vi.mock('../composables/useMemoryMetrics', () => ({ useMemoryMetrics: composableMock.use }))

function state() {
  const stats = { sampleCount: 1, averageBytes: 1_048_576, p50Bytes: 1_048_576, p90Bytes: 1_048_576, p95Bytes: 1_048_576, p99Bytes: 1_048_576, status: 'ok' }
  return {
    summary: ref({ appId: 'demo', from: '', to: '', pss: stats, vss: stats, javaHeap: stats, status: 'ok', dataSource: 'memory' }), summaryLoading: ref(false), summaryError: ref(null),
    trend: ref({ appId: 'demo', from: '', to: '', metric: 'pss', interval: 'hour', points: [], status: 'no_data', dataSource: 'memory' }), trendLoading: ref(false), trendError: ref(null),
    loading: ref(false), load: vi.fn(), loadSummary: vi.fn(), loadTrend: vi.fn(), cancel: vi.fn(),
  }
}

describe('MemoryMetricsView', () => {
  beforeEach(() => { vi.resetAllMocks(); routeMock.query = {}; routeMock.fullPath = '/apps/demo/memory-metrics'; composableMock.use.mockReturnValue(state()) })

  it('仅展示三个内存页签和统计趋势，不出现 FD、32/64 或多维下钻入口', async () => {
    const wrapper = mount(MemoryMetricsView, { global: { stubs: {
      AppLayout: { template: '<div><slot /></div>' }, MemoryMetricFilterBar: { template: '<div />' },
      MemoryMetricTrendChart: { template: '<div />' },
    } } })
    expect(wrapper.findAll('.memory-tabs button')).toHaveLength(3)
    expect(wrapper.text()).toContain('PSS')
    expect(wrapper.text()).toContain('P95')
    expect(wrapper.text()).not.toMatch(/FD|32.?64|多维|下钻/)
    await wrapper.findAll('.memory-tabs button')[1].trigger('click')
    expect(routerMock.push).toHaveBeenCalledWith(expect.objectContaining({ name: 'memory-metrics', params: { appId: 'demo' }, query: expect.objectContaining({ metric: 'vss' }) }))
  })
})
