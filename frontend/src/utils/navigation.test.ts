import { describe, expect, it } from 'vitest'
import { safeRedirect } from './navigation'

describe('safeRedirect', () => {
  it('allows a site-local path', () => {
    expect(safeRedirect('/apps/mobile-apm/crashes')).toBe('/apps/mobile-apm/crashes')
  })

  it('rejects external and malformed targets', () => {
    expect(safeRedirect('https://evil.example')).toBe('/apps')
    expect(safeRedirect('//evil.example')).toBe('/apps')
    expect(safeRedirect(null)).toBe('/apps')
  })
})
