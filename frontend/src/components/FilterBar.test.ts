import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import FilterBar from './FilterBar.vue'
import { createDefaultFilters } from '../utils/query'

describe('FilterBar', () => {
  it('emits the edited filter set when submitted', async () => {
    const wrapper = mount(FilterBar, {
      props: {
        modelValue: createDefaultFilters(new Date('2026-08-19T12:00:00.000Z')),
      },
    })

    await wrapper.get('#app-version').setValue('3.2.0')
    await wrapper.get('#environment').setValue('production')
    await wrapper.get('form').trigger('submit')

    const submitted = wrapper.emitted('submit')?.[0]?.[0] as { appVersion: string; environment: string }
    expect(submitted.appVersion).toBe('3.2.0')
    expect(submitted.environment).toBe('production')
  })
})
