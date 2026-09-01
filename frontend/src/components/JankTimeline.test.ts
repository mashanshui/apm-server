import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import JankTimeline from './JankTimeline.vue'

describe('JankTimeline', () => {
  it('分开绘制覆盖和空洞，并从堆栈字典展示实际帧', async () => {
    const wrapper = mount(JankTimeline, {
      props: {
        exactMessageDurationNs: 12_000_000,
        slices: [
          { startOffsetNs: 0, endOffsetNs: 4_000_000, durationNs: 4_000_000, stackId: 'stack-1', covered: true },
          { startOffsetNs: 4_000_000, endOffsetNs: 8_000_000, durationNs: 4_000_000, stackId: null, covered: false },
        ],
        stackDictionary: {
          'stack-1': [{ className: 'com.example.Feed', methodName: 'bind', fileName: 'Feed.kt', lineNumber: 42, applicationFrame: true }],
        },
      },
    })

    expect(wrapper.findAll('.timeline-covered')).toHaveLength(1)
    expect(wrapper.findAll('.timeline-gap')).toHaveLength(1)
    await wrapper.get('.timeline-covered').trigger('click')
    expect(wrapper.text()).toContain('com.example.Feed.bind')
    expect(wrapper.text()).toContain('Feed.kt:42')
    await wrapper.get('.timeline-gap').trigger('click')
    expect(wrapper.text()).toContain('此区间没有成功采样，不分配给任何方法')
  })

  it('没有时间片时展示明确空状态', () => {
    const wrapper = mount(JankTimeline, { props: { slices: [], stackDictionary: {}, exactMessageDurationNs: 0 } })
    expect(wrapper.text()).toContain('没有可展示的采样时间片')
  })
})
