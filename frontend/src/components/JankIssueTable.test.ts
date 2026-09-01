import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import JankIssueTable from './JankIssueTable.vue'
import type { JankIssueSummary } from '../types/jank'

const issue: JankIssueSummary = {
  fingerprint: 'jank-fingerprint-1',
  fingerprintVersion: 'v1',
  scene: 'feed',
  algorithmVersion: 'jank-v1',
  eventCount: 3,
  affectedSessionCount: 2,
  affectedDeviceCount: 2,
  firstSeenAt: '2026-08-15T10:00:00Z',
  lastSeenAt: '2026-08-15T10:10:00Z',
  exactMessageDuration: { p50Ms: 800, p90Ms: 1_200, p99Ms: null },
  estimatedStackDuration: { p50Ms: 600, p90Ms: 900, p99Ms: null },
}

describe('JankIssueTable', () => {
  it('区分精确消息耗时和采样估算耗时，空分位数不显示为零', async () => {
    const wrapper = mount(JankIssueTable, { props: { issues: [issue], nextCursor: null } })

    expect(wrapper.text()).toContain('精确消息 P50 / P90 / P99')
    expect(wrapper.text()).toContain('采样估算 P50 / P90 / P99')
    expect(wrapper.text()).toContain('800 ms / 1,200 ms / —')
    expect(wrapper.text()).toContain('600 ms / 900 ms / —')
    expect(wrapper.text()).not.toContain('负责人')
    expect(wrapper.text()).not.toContain('处理状态')

    await wrapper.get('button.fingerprint').trigger('click')
    expect(wrapper.emitted('open')?.[0]).toEqual([issue])
  })

  it('展示无数据状态并禁用无游标的追加按钮', () => {
    const wrapper = mount(JankIssueTable, { props: { issues: [], nextCursor: null } })
    expect(wrapper.text()).toContain('当前筛选范围没有卡顿问题')
    expect(wrapper.find('.pagination-bar').exists()).toBe(false)
  })
})
