import { ref } from 'vue'
import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import MemoryLeakReportsView from './MemoryLeakReportsView.vue'

const routerMock = vi.hoisted(() => ({ push: vi.fn() }))
const routeMock = vi.hoisted(() => ({ params: { appId: 'demo' }, query: {}, fullPath: '/apps/demo/memory-leaks' }))
const composableMock = vi.hoisted(() => ({ use: vi.fn() }))
vi.mock('vue-router', () => ({ useRoute: () => routeMock, useRouter: () => routerMock }))
vi.mock('../composables/useMemoryLeakReports', () => ({ useMemoryLeakReports: composableMock.use }))

function state() {
  return {
    issues: ref({ appId: 'demo', from: '', to: '', total: 0, totalOccurrences: 0, totalAffectedDevices: 0, page: 1, pageSize: 20, items: [], status: 'no_data', dataSource: 'memory' }),
    issuesLoading: ref(false), issuesError: ref(null), trend: ref({ appId: 'demo', from: '', to: '', interval: 'hour', points: [], status: 'no_data', dataSource: 'memory' }), trendLoading: ref(false), trendError: ref(null), loading: ref(false),
    load: vi.fn(), loadIssues: vi.fn(), loadTrend: vi.fn(), cancel: vi.fn(),
  }
}

describe('MemoryLeakReportsView', () => {
  beforeEach(() => { vi.resetAllMocks(); routeMock.query = {}; routeMock.fullPath = '/apps/demo/memory-leaks'; composableMock.use.mockReturnValue(state()) })

  it('提供趋势指标切换、空状态和查询范围边界文案', async () => {
    const wrapper = mount(MemoryLeakReportsView, { global: { stubs: {
      AppLayout: { template: '<div><slot /></div>' }, MemoryLeakFilterBar: { template: '<div />' }, MemoryLeakTrendChart: { template: '<div />' },
    } } })
    expect(wrapper.text()).toContain('SDK 报告的疑似问题')
    expect(wrapper.text()).toContain('当前筛选范围没有趋势数据')
    expect(wrapper.text()).toContain('当前筛选范围没有 SDK 报告的疑似问题')
    await wrapper.findAll('.segmented-control button')[1].trigger('click')
    expect(routerMock.push).toHaveBeenCalledWith(expect.objectContaining({ name: 'memory-leaks', query: expect.objectContaining({ trendMetric: 'affectedDevices' }) }))
  })
})
