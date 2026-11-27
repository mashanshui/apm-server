import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import DiagnosticLogcat from './DiagnosticLogcat.vue'
import type { DiagnosticLogcat as DiagnosticLogcatData } from '../types/crashDiagnostics'

/** 合成三个缓冲区：成功含日志、权限失败、成功空结果。 */
function logcat(): DiagnosticLogcatData {
  return { status: 'partial', reason: 'permission_denied', truncated: false,
    main: { status: 'ok', truncated: false, lines: ['<img src=x onerror=alert(1)>'] },
    system: { status: 'unavailable', reason: 'permission_denied', truncated: false, lines: [] },
    events: { status: 'ok', truncated: false, lines: [] } }
}

describe('DiagnosticLogcat', () => {
  /** 每个缓冲区保留失败与成功空结果，日志行以纯文本展示。 */
  it('distinguishes unavailable, empty and absent buffers and escapes lines', async () => {
    const wrapper = mount(DiagnosticLogcat, { props: { logcat: logcat() } })
    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>')
    expect(wrapper.find('img').exists()).toBe(false)
    const buffers = wrapper.findAll('.segmented-control button')
    await buffers[1].trigger('click')
    expect(wrapper.text()).toContain('权限受限')
    expect(wrapper.text()).toContain('没有可展示的日志')
    await buffers[2].trigger('click')
    expect(wrapper.text()).toContain('采集成功，日志为空')
    wrapper.unmount()
  })

  /** 更换事件后回到 main 缓冲区；未采集时不出现缓冲区切换。 */
  it('resets buffer selection on event change and marks absent logs', async () => {
    const wrapper = mount(DiagnosticLogcat, { props: { logcat: logcat() } })
    await wrapper.findAll('.segmented-control button')[2].trigger('click')
    await wrapper.setProps({ logcat: logcat() })
    expect(wrapper.findAll('.segmented-control button')[0].attributes('aria-selected')).toBe('true')
    await wrapper.setProps({ logcat: null })
    expect(wrapper.text()).toContain('未采集日志')
    expect(wrapper.find('.segmented-control').exists()).toBe(false)
    wrapper.unmount()
  })
})
