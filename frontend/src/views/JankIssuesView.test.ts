import { ref } from 'vue'
import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { JankIssueSummary } from '../types/jank'
import JankIssuesView from './JankIssuesView.vue'

const routerMock = vi.hoisted(() => ({ push: vi.fn() }))
const routeMock = vi.hoisted(() => ({ params: { appId: 'demo' }, query: {}, fullPath: '/apps/demo/janks' }))
const composableMock = vi.hoisted(() => ({ use: vi.fn() }))

vi.mock('vue-router', () => ({
  useRoute: () => routeMock,
  useRouter: () => routerMock,
  RouterLink: { template: '<a><slot /></a>' },
}))
vi.mock('../composables/useJankIssues', () => ({ useJankIssues: composableMock.use }))

const issue: JankIssueSummary = {
  fingerprint: 'fingerprint-1', fingerprintVersion: 'v1', scene: 'feed', algorithmVersion: 'jank-v1',
  eventCount: 2, affectedSessionCount: 1, affectedDeviceCount: 1,
  firstSeenAt: '2026-08-15T10:00:00Z', lastSeenAt: '2026-08-15T10:01:00Z',
  exactMessageDuration: { p50Ms: 700, p90Ms: null, p99Ms: null },
  estimatedStackDuration: { p50Ms: 500, p90Ms: null, p99Ms: null },
}

function queryState() {
  return {
    overview: ref(null), overviewLoading: ref(false), overviewError: ref('总览暂时不可用'),
    trend: ref({ appId: 'demo', from: '', to: '', interval: 'hour', points: [], status: 'no_data', dataSource: 'memory' }), trendLoading: ref(false), trendError: ref(null),
    issues: ref([issue]), nextCursor: ref(null), issuesLoading: ref(false), issuesLoadingMore: ref(false), issuesError: ref(null), issueAppendError: ref(null), loading: ref(false),
    load: vi.fn(), loadOverview: vi.fn(), loadTrend: vi.fn(), loadIssues: vi.fn(), loadMoreIssues: vi.fn(), cancel: vi.fn(),
  }
}

describe('JankIssuesView', () => {
  beforeEach(() => { vi.resetAllMocks(); composableMock.use.mockReturnValue(queryState()) })

  it('部分请求失败时保留成功的 Issue 区域并支持下钻', async () => {
    const wrapper = mount(JankIssuesView, {
      global: { stubs: {
        AppLayout: { template: '<div><slot /></div>' },
        JankFilterBar: { template: '<div />' },
        JankTrendChart: { template: '<div />' },
        RouterLink: { template: '<a><slot /></a>' },
      } },
    })

    expect(wrapper.text()).toContain('总览暂时不可用')
    expect(wrapper.text()).toContain('fingerprint-1')
    expect(wrapper.text()).not.toContain('负责人')
    await wrapper.get('button.fingerprint').trigger('click')
    expect(routerMock.push).toHaveBeenCalledWith(expect.objectContaining({
      name: 'jank-issue-events',
      params: { appId: 'demo', fingerprint: 'fingerprint-1' },
    }))
  })
})
