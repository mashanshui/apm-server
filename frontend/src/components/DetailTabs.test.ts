import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import DetailTabs from './DetailTabs.vue'

/** 固定的三个分区，覆盖选中态与切换回传。 */
const tabs = [{ key: 'stack', label: '崩溃堆栈' }, { key: 'logcat', label: '日志' }, { key: 'process', label: '进程信息' }]

describe('DetailTabs', () => {
  /** 选中项独占 Tab 焦点，所有标签指向对应面板。 */
  it('marks the selected tab for assistive technology', () => {
    const wrapper = mount(DetailTabs, { props: { tabs, modelValue: 'logcat', idPrefix: 'detail' } })
    const buttons = wrapper.findAll('button')
    expect(buttons.map(button => button.text())).toEqual(['崩溃堆栈', '日志', '进程信息'])
    expect(buttons.map(button => button.attributes('id'))).toEqual(['detail-tab-stack', 'detail-tab-logcat', 'detail-tab-process'])
    expect(buttons.map(button => button.attributes('aria-controls'))).toEqual(['detail-panel-stack', 'detail-panel-logcat', 'detail-panel-process'])
    expect(buttons.map(button => button.attributes('tabindex'))).toEqual(['-1', '0', '-1'])
    expect(buttons[1].attributes('aria-selected')).toBe('true')
    expect(buttons[1].classes()).toContain('active')
    expect(buttons[0].attributes('aria-selected')).toBe('false')
    expect(buttons[0].classes()).not.toContain('active')
    wrapper.unmount()
  })

  /** 点击只回传分区 key，由父页面决定面板显隐。 */
  it('emits the clicked key without owning panel state', async () => {
    const wrapper = mount(DetailTabs, { props: { tabs, modelValue: 'stack', idPrefix: 'detail' } })
    await wrapper.findAll('button')[2].trigger('click')
    expect(wrapper.emitted('update:modelValue')).toEqual([['process']])
    expect(wrapper.findAll('button')[0].attributes('aria-selected')).toBe('true')
    wrapper.unmount()
  })

  /** 方向键首尾循环，Home/End 跳转两端，并同步实际键盘焦点。 */
  it('moves focus and selects tabs with arrow, Home and End keys', async () => {
    /** 将组件挂入文档以验证实际焦点。 */
    const wrapper = mount(DetailTabs, { props: { tabs, modelValue: 'stack', idPrefix: 'detail' }, attachTo: document.body })
    /** 标签顺序与输入列表一致。 */
    const buttons = wrapper.findAll('button')
    ;(buttons[0].element as HTMLButtonElement).focus()
    await buttons[0].trigger('keydown', { key: 'ArrowLeft' })
    expect(wrapper.emitted('update:modelValue')).toEqual([['process']])
    expect(document.activeElement).toBe(buttons[2].element)
    await wrapper.setProps({ modelValue: 'process' })
    expect(buttons.map(button => button.attributes('tabindex'))).toEqual(['-1', '-1', '0'])

    await buttons[2].trigger('keydown', { key: 'ArrowRight' })
    expect(document.activeElement).toBe(buttons[0].element)
    expect(wrapper.emitted('update:modelValue')?.[1]).toEqual(['stack'])
    await wrapper.setProps({ modelValue: 'stack' })
    await buttons[0].trigger('keydown', { key: 'End' })
    expect(document.activeElement).toBe(buttons[2].element)
    expect(wrapper.emitted('update:modelValue')?.[2]).toEqual(['process'])
    await wrapper.setProps({ modelValue: 'process' })
    await buttons[2].trigger('keydown', { key: 'Home' })
    expect(document.activeElement).toBe(buttons[0].element)
    expect(wrapper.emitted('update:modelValue')?.[3]).toEqual(['stack'])
    await buttons[0].trigger('keydown', { key: 'ArrowDown' })
    expect(wrapper.emitted('update:modelValue')).toHaveLength(4)
    wrapper.unmount()
  })
})
