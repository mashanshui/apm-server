import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import JankCallTree from './JankCallTree.vue'

describe('JankCallTree', () => {
  it('递归展示有限字段，深层节点默认折叠且按钮可获得键盘焦点', async () => {
    const longName = 'veryLongMethodName'.repeat(12)
    const wrapper = mount(JankCallTree, { attachTo: document.body, props: { nodes: [{
      className: 'com.example.Root', methodName: 'run', estimatedDurationNs: 10_000_000, estimatedUnattributedDurationNs: 1_000_000,
      children: [{ className: 'com.example.Child', methodName: longName, estimatedDurationNs: 8_000_000, estimatedUnattributedDurationNs: 2_000_000,
        children: [{ className: 'com.example.Deep', methodName: 'work', estimatedDurationNs: 4_000_000, estimatedUnattributedDurationNs: 1_000_000,
          children: [{ className: 'com.example.Hidden', methodName: 'leaf', estimatedDurationNs: 2_000_000, estimatedUnattributedDurationNs: 2_000_000, children: [] }] }],
      }],
    }] } })

    expect(wrapper.text()).toContain(longName)
    expect(wrapper.text()).not.toContain('com.example.Hidden.leaf')
    const toggles = wrapper.findAll('.tree-toggle')
    expect(toggles[2].attributes('aria-expanded')).toBe('false')
    ;(toggles[2].element as HTMLElement).focus()
    expect(document.activeElement).toBe(toggles[2].element)
    await toggles[2].trigger('click')
    expect(wrapper.text()).toContain('com.example.Hidden.leaf')
    expect(wrapper.text()).not.toContain('.java:')
    wrapper.unmount()
  })

  it('空树展示明确状态', () => {
    const wrapper = mount(JankCallTree, { props: { nodes: [] } })
    expect(wrapper.text()).toContain('没有可展示的采样估算调用树')
  })
})
