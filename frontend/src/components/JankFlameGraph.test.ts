import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import JankFlameGraph from './JankFlameGraph.vue'

describe('JankFlameGraph', () => {
  it('提供 SVG 节点提示和可访问树形替代内容', async () => {
    const wrapper = mount(JankFlameGraph, { props: { nodes: [{
      className: 'com.example.Main', methodName: 'render', estimatedDurationNs: 5_000_000,
      estimatedUnattributedDurationNs: 1_000_000, children: [],
    }] } })
    expect(wrapper.get('svg').attributes('aria-label')).toBe('采样估算火焰图')
    expect(wrapper.get('title').text()).toContain('采样估算 5 ms')
    expect(wrapper.text()).toContain('可访问的树形替代内容')
    expect(wrapper.text()).not.toContain('CPU')
  })

  it('空数据不生成虚假矩形', () => {
    const wrapper = mount(JankFlameGraph, { props: { nodes: [] } })
    expect(wrapper.find('svg').exists()).toBe(false)
    expect(wrapper.text()).toContain('没有可展示的采样估算火焰图')
  })
})
