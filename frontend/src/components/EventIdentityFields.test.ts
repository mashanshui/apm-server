import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import EventIdentityFields from './EventIdentityFields.vue'

const values = {
  anonymousDeviceId: '11111111-1111-4111-8111-111111111111',
  sessionId: '22222222-2222-4222-8222-222222222222',
  processId: '22222222-2222-4222-8222-222222222222',
}

describe('EventIdentityFields', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('分别展示设备、启动和进程 ID，即使值相同也不合并', () => {
    const wrapper = mount(EventIdentityFields, { props: values })

    expect(wrapper.findAll('dt').map((item) => item.text())).toEqual(['设备 ID', '启动 ID', '进程 ID'])
    expect(wrapper.findAll('code').map((item) => item.text())).toEqual([
      values.anonymousDeviceId,
      values.sessionId,
      values.processId,
    ])
    expect(wrapper.findAll('button')).toHaveLength(3)
  })

  it('缺失值显示占位符并禁用对应复制按钮', () => {
    const wrapper = mount(EventIdentityFields, {
      props: { anonymousDeviceId: null, sessionId: '', processId: null },
    })

    expect(wrapper.findAll('code').map((item) => item.text())).toEqual(['—', '—', '—'])
    expect(wrapper.findAll('button').every((button) => (button.element as HTMLButtonElement).disabled)).toBe(true)
  })

  it('复制完整值并反馈成功', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    const wrapper = mount(EventIdentityFields, { props: values })

    await wrapper.get('button[aria-label="复制进程 ID"]').trigger('click')

    expect(writeText).toHaveBeenCalledWith(values.processId)
    expect(wrapper.get('[role="status"]').text()).toContain('已复制进程 ID')
  })

  it('剪贴板拒绝时反馈失败且空值不会调用剪贴板', async () => {
    const writeText = vi.fn().mockRejectedValue(new Error('denied'))
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    const wrapper = mount(EventIdentityFields, {
      props: { anonymousDeviceId: null, sessionId: 'session', processId: 'process' },
    })

    await wrapper.get('button[aria-label="复制进程 ID"]').trigger('click')
    expect(wrapper.get('[role="alert"]').text()).toContain('复制进程 ID 失败')
    await wrapper.get('button[aria-label="复制启动 ID"]').trigger('click')
    expect(writeText).toHaveBeenCalledTimes(2)
    expect(writeText).toHaveBeenLastCalledWith('session')
    expect(wrapper.find('button[aria-label="复制设备 ID"]').element).toHaveProperty('disabled', true)
  })
})
