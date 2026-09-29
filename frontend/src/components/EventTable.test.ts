import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import EventTable from './EventTable.vue'

describe('EventTable', () => {
  it('keeps the shown page and disables continuation after invalid cursor', async () => {
    const wrapper = mount(EventTable, {
      props: {
        events: [{ eventId: 'event-1', occurredAt: '2026-09-28T10:00:00Z',
          appVersion: '1.0', versionCode: 1, buildId: 'build-1', channel: 'official',
          environment: 'production', osVersion: '16', deviceModel: 'Pixel',
          sessionId: 's1', anonymousDeviceId: 'd1', exceptionType: 'java.lang.Error',
          fingerprint: 'fp-1', symbolicationStatus: 'raw_only' }],
        nextCursor: 'old-cursor', cursorInvalid: true,
      },
    })
    expect(wrapper.text()).toContain('游标已失效，请重新查询')
    expect(wrapper.get('.pagination-actions button').attributes('disabled')).toBeDefined()
    await wrapper.get('.pagination-actions button').trigger('click')
    expect(wrapper.emitted('next')).toBeUndefined()
  })
})
