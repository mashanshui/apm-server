import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import JankMetricSummary from './JankMetricSummary.vue'

describe('JankMetricSummary', () => {
  it('保留合法 FPS 零值并隔离多个算法版本', () => {
    const wrapper = mount(JankMetricSummary, { props: { metric: 'fps', response: {
      appId: 'demo', from: '', to: '', status: 'ok', dataSource: 'memory', metrics: [
        { algorithmVersion: 'fps-v1', totalRecords: 2, validRecords: 2, averageFps: 0, p50Fps: 0, p90Fps: 0, p99Fps: 0, status: 'ok' },
        { algorithmVersion: 'fps-v2', totalRecords: 1, validRecords: 0, averageFps: null, p50Fps: null, p90Fps: null, p99Fps: null, status: 'no_valid_data' },
      ],
    } } })
    expect(wrapper.text()).toContain('算法 fps-v1')
    expect(wrapper.text()).toContain('算法 fps-v2')
    expect(wrapper.text()).toContain('0 帧/秒')
    expect(wrapper.text()).toContain('没有有效 FPS 记录')
    expect(wrapper.text()).toContain('有效 FPS 记录')
    expect(wrapper.text()).not.toContain('有效设备日')
  })

  it('挂起率分母不足时保持空值并使用设备日口径', () => {
    const wrapper = mount(JankMetricSummary, { props: { metric: 'suspension_rate', response: {
      appId: 'demo', from: '', to: '', status: 'denominator_insufficient', dataSource: 'memory', metrics: [
        { algorithmVersion: 'susp-v1', totalRecords: 3, validDeviceDayRecords: 0, averageSecondsPerHour: null, p50SecondsPerHour: null, p90SecondsPerHour: null, p99SecondsPerHour: null, status: 'denominator_insufficient' },
      ],
    } } })
    expect(wrapper.text()).toContain('前台时长分母不足')
    expect(wrapper.text()).toContain('有效设备日')
    expect(wrapper.text()).toContain('—')
    expect(wrapper.text()).not.toContain('有效 FPS 记录')
  })

  it('no_data 响应显示无记录而不是零值卡片', () => {
    const wrapper = mount(JankMetricSummary, { props: { metric: 'fps', response: { appId: 'demo', from: '', to: '', status: 'no_data', dataSource: 'memory', metrics: [] } } })
    expect(wrapper.text()).toContain('当前范围无记录')
    expect(wrapper.find('.metric-stat').exists()).toBe(false)
  })
})
