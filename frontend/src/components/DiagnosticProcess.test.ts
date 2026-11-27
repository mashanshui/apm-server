import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import DiagnosticProcess from './DiagnosticProcess.vue'

describe('DiagnosticProcess', () => {
  /** 进程与设备分区状态独立展示，未知前后台保留为未知。 */
  it('renders process and device partitions independently', () => {
    const wrapper = mount(DiagnosticProcess, { props: {
      process: { status: 'ok', truncated: false, pid: 47, foreground: false },
      device: { status: 'ok', truncated: false, apiLevel: 29, abis: ['arm64-v8a'] } } })
    expect(wrapper.text()).toContain('进程：采集成功')
    expect(wrapper.text()).toContain('设备：采集成功')
    expect(wrapper.text()).toContain('47')
    expect(wrapper.text()).toContain('后台')
    expect(wrapper.text()).toContain('29')
    expect(wrapper.text()).toContain('arm64-v8a')
    wrapper.unmount()
  })

  /** 两个分区都缺失时不伪造进程或设备字段。 */
  it('keeps missing partitions explicit', () => {
    const wrapper = mount(DiagnosticProcess, { props: { process: null, device: null } })
    expect(wrapper.text()).toContain('进程：未采集 · 设备：未采集')
    expect(wrapper.text()).toContain('未知')
    expect(wrapper.text()).toContain('—')
    wrapper.unmount()
  })
})
