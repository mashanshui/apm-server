import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import DiagnosticMemory from './DiagnosticMemory.vue'

describe('DiagnosticMemory', () => {
  /** 零值合法保留，未知指标不伪造数值，proc 原文区分空与缺失。 */
  it('keeps zero bytes, unknown metrics and empty proc text distinct', () => {
    const wrapper = mount(DiagnosticMemory, { props: { memory: { status: 'ok', truncated: false,
      javaHeapUsedBytes: 0, totalPssBytes: 1024, meminfo: '', statusText: 'VmRSS: 1 kB' } } })
    expect(wrapper.text()).toContain('0 B')
    expect(wrapper.text()).toContain('1.0 KiB（1,024 B）')
    expect(wrapper.text()).toContain('—')
    expect(wrapper.text()).toContain('内容为空。')
    expect(wrapper.text()).toContain('未取得原文。')
    expect(wrapper.text()).toContain('VmRSS: 1 kB')
    wrapper.unmount()
  })

  /** 未采集内存时给出明确空态，不展示指标网格。 */
  it('marks absent memory without a metric grid', () => {
    const wrapper = mount(DiagnosticMemory, { props: { memory: null } })
    expect(wrapper.text()).toContain('未采集内存信息')
    expect(wrapper.text()).toContain('未采集')
    expect(wrapper.find('.diagnostic-grid').exists()).toBe(false)
    wrapper.unmount()
  })
})
