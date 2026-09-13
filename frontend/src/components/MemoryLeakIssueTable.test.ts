import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import MemoryLeakIssueTable from './MemoryLeakIssueTable.vue'

const issue = {
  signature: 'sig-<x>', leakClass: 'com.example.<Activity>', leakReason: 'large array', gcRoot: 'thread <main>',
  path: [{ reference: 'owner<one>', referenceType: 'field', declaredClass: 'Holder' }, { reference: 'array[0]', referenceType: 'array' }],
  lastOccurredAt: '2026-01-01T00:00:00Z', occurrences: 3, occurrenceRatio: 0.75, affectedDevices: 2, deviceRatio: 1, versions: ['1.0.0'],
}

describe('MemoryLeakIssueTable', () => {
  it('展开长引用链并按普通文本渲染特殊字符，不展示无来源内存量', async () => {
    const wrapper = mount(MemoryLeakIssueTable, { props: { issues: [issue], total: 1, page: 1, pageSize: 20 } })
    expect(wrapper.html()).toContain('&lt;Activity&gt;')
    expect(wrapper.text()).toContain('75.0%')
    expect(wrapper.text()).not.toMatch(/retained|最大泄漏内存|P50/)
    await wrapper.find('.memory-leak-path-cell .link-button').trigger('click')
    expect(wrapper.text()).toContain('owner<one>')
    expect(wrapper.text()).toContain('array[0]')
    expect(wrapper.findAll('.memory-leak-expanded-row')).toHaveLength(1)
  })
})
