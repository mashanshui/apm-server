import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AnrStackPanel from './AnrStackPanel.vue'

/** 原文始终可读，每个分区按实际状态决定还原按钮。 */
describe('ANR 堆栈视图', () => {
  it.each([
    ['pending', '等待 Java 还原'],
    ['failed', 'Java 还原失败'],
    ['not_applicable', '没有可还原的 Java 帧'],
  ] as const)('保留 %s 的原文', (status, label) => {
    const wrapper = mount(AnrStackPanel, { props: { title: '主线程', raw: 'raw', eventId: 'event', view: { status, reason: 'mapping_missing' } } })
    expect(wrapper.text()).toContain(label)
    expect(wrapper.text()).toContain('raw')
    expect(wrapper.findAll('button')[1].attributes('disabled')).toBeDefined()
  })

  it('独立切换部分结果并保留采集失败原因', async () => {
    const wrapper = mount(AnrStackPanel, { props: { title: '系统 trace（采集片段）', raw: 'original', eventId: 'event', trace: true,
      part: { status: 'partial', reason: 'size_limit', truncated: true, source: 'art_first_write' },
      view: { status: 'partial', text: 'restored\n- locked', symbolFileId: 'mapping', symbolFileRevision: 1 } } })
    expect(wrapper.text()).toContain('部分采集 · 大小受限')
    expect(wrapper.text()).toContain('ART 首次写入')
    await wrapper.findAll('button')[1].trigger('click')
    expect(wrapper.text()).toContain('restored')
    await wrapper.findAll('button')[0].trigger('click')
    expect(wrapper.text()).toContain('original')
  })
})
