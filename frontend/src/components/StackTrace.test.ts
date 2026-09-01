import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import StackTrace from './StackTrace.vue'

describe('StackTrace', () => {
  it('renders the sanitized throwable chain and application frame marker', () => {
    const wrapper = mount(StackTrace, {
      props: {
        crash: {
          kind: 'jvm',
          fatal: true,
          throwableChain: [{
            type: 'java.lang.IllegalStateException',
            message: 'sanitized message',
            frames: [{
              className: 'com.example.PaymentActivity',
              methodName: 'submit',
              fileName: 'PaymentActivity.kt',
              lineNumber: 120,
              applicationFrame: true,
            }],
          }],
        },
      },
    })

    expect(wrapper.text()).toContain('java.lang.IllegalStateException')
    expect(wrapper.text()).toContain('sanitized message')
    expect(wrapper.text()).toContain('PaymentActivity.kt:120')
    expect(wrapper.text()).toContain('APP')
  })
})
