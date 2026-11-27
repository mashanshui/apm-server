import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import DiagnosticOpenFiles from './DiagnosticOpenFiles.vue'

describe('DiagnosticOpenFiles', () => {
  /** 总数零与明细为空是有效结果；权限失败不冒充成功空明细。 */
  it('separates empty success from unavailable collection', () => {
    const empty = mount(DiagnosticOpenFiles, { props: { openFiles: { status: 'ok', truncated: false, count: 0, entries: [] } } })
    expect(empty.text()).toContain('总数 0')
    expect(empty.text()).toContain('采集成功，没有文件描述符明细')
    empty.unmount()
    const denied = mount(DiagnosticOpenFiles, { props: { openFiles: { status: 'unavailable', reason: 'permission_denied', truncated: false, entries: [] } } })
    expect(denied.text()).toContain('权限受限')
    expect(denied.text()).toContain('没有可展示的文件描述符明细')
    denied.unmount()
  })

  /** 明细保留编号与脱敏目标；未采集时给出空态。 */
  it('renders bounded entries and marks absent collection', () => {
    const wrapper = mount(DiagnosticOpenFiles, { props: { openFiles: { status: 'partial', reason: 'count_limit', truncated: true, count: 9,
      entries: [{ fd: 3, target: 'socket' }, { fd: 4, target: null }] } } })
    expect(wrapper.text()).toContain('总数 9 · 已保存 2 条')
    expect(wrapper.text()).toContain('socket')
    expect(wrapper.text()).toContain('目标未取得')
    expect(wrapper.text()).toContain('数量受限')
    wrapper.unmount()
    const absent = mount(DiagnosticOpenFiles, { props: { openFiles: null } })
    expect(absent.text()).toContain('未采集文件描述符')
    expect(absent.find('.fd-list').exists()).toBe(false)
    absent.unmount()
  })
})
