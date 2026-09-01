import { describe, expect, it } from 'vitest'
import { createDefaultFilters, filtersToQuery, parseFilters, toApiFilters } from './query'

describe('Crash filter query state', () => {
  it('parses and serializes filter state without losing dimensions', () => {
    const query = {
      from: '2026-08-19T00:00:00Z',
      to: '2026-08-19T01:00:00Z',
      appVersion: '3.2.0',
      channel: 'official',
      environment: 'production',
      osVersion: '16',
      deviceModel: 'Pixel-8',
      fingerprint: 'fp/a',
      interval: 'day',
    }
    const filters = parseFilters(query)
    expect(filters.fingerprint).toBe('fp/a')
    expect(filters.interval).toBe('day')
    expect(filtersToQuery(filters)).toMatchObject(query)
    expect(toApiFilters(filters)).toMatchObject({
      appVersion: '3.2.0',
      fingerprint: 'fp/a',
      limit: 50,
      timeoutMs: 2000,
    })
  })

  it('uses the latest 24 hours when no range is supplied', () => {
    const now = new Date('2026-08-19T12:00:00.000Z')
    const filters = createDefaultFilters(now)
    expect(filters.to).toBe('2026-08-19T12:00:00.000Z')
    expect(filters.from).toBe('2026-08-18T12:00:00.000Z')
    expect(filters.interval).toBe('hour')
  })
})
