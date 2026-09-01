import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import IssueTable from './IssueTable.vue'

const issue = {
  fingerprint: 'fp-001',
  fingerprintVersion: 'v1',
  exceptionType: 'java.lang.IllegalStateException',
  eventCount: 3,
  crashedSessionCount: 2,
  affectedDeviceCount: 2,
  firstSeenAt: '2026-08-19T00:00:00Z',
  lastSeenAt: '2026-08-19T01:00:00Z',
}

describe('IssueTable', () => {
  it('emits issue navigation and cursor pagination actions', async () => {
    const wrapper = mount(IssueTable, {
      props: { issues: [issue], nextCursor: 'fp-001' },
    })

    await wrapper.get('.link-button').trigger('click')
    await wrapper.get('.pagination-actions button').trigger('click')

    expect(wrapper.emitted('open')?.[0]?.[0]).toEqual(issue)
    expect(wrapper.emitted('next')).toHaveLength(1)
  })
})
