import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import MemoryMetricSummary from './MemoryMetricSummary.vue'

describe('MemoryMetricSummary', () => {
  it('按 MiB 保留零值并展示五张统计卡片', () => {
    const wrapper = mount(MemoryMetricSummary, { props: { metric: 'pss', stats: {
      sampleCount: 4, averageBytes: 1_048_576, p50Bytes: 0, p90Bytes: 2_097_152,
      p95Bytes: null, p99Bytes: 3_145_728, status: 'ok',
    } } })
    expect(wrapper.findAll('.metric-stat')).toHaveLength(5)
    expect(wrapper.text()).toContain('0.00 MiB')
    expect(wrapper.text()).toContain('1.00 MiB')
    expect(wrapper.text()).toContain('—')
    expect(wrapper.text()).toContain('4 个样本')
  })
})
