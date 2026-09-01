import { afterEach, describe, expect, it, vi } from 'vitest'
import { buildQueryParams, crashApi } from './crashApi'

afterEach(() => {
  vi.restoreAllMocks()
})

describe('crashApi', () => {
  it('builds supported query parameters and omits empty values', () => {
    const params = buildQueryParams({
      from: '2026-08-19T00:00:00Z',
      to: '2026-08-19T01:00:00Z',
      appVersion: '3.2.0',
      channel: '',
      limit: 50,
      timeoutMs: 2000,
    })

    expect(params.toString()).toContain('from=2026-08-19T00%3A00%3A00Z')
    expect(params.get('appVersion')).toBe('3.2.0')
    expect(params.get('channel')).toBeNull()
    expect(params.get('limit')).toBe('50')
  })

  it('scopes requests to the app without sending an ingest key', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      appId: 'demo-app',
      from: '2026-08-19T00:00:00Z',
      to: '2026-08-19T01:00:00Z',
      stats: {
        startedSessions: 1,
        crashEvents: 0,
        crashedSessions: 0,
        affectedDevices: 0,
        crashRatePer1000Sessions: 0,
        crashFreeSessionRate: 1,
        status: 'ok',
      },
      dataSource: 'memory',
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)

    await crashApi.overview('demo/app', { limit: 50 })

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/api/v1/apps/demo%2Fapp/crashes/overview?limit=50')
    expect((init.headers as Record<string, string>)['X-App-Id']).toBeUndefined()
    expect((init.headers as Record<string, string>)['X-App-Key']).toBeUndefined()
    expect(init.credentials).toBe('include')
  })

  it('passes the trend interval as a dedicated query parameter', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      appId: 'demo-app',
      from: '2026-08-19T00:00:00Z',
      to: '2026-08-19T01:00:00Z',
      interval: 'day',
      points: [],
      dataSource: 'memory',
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)

    await crashApi.trend('demo-app', {}, 'day')

    expect(String(fetchMock.mock.calls[0][0])).toContain('interval=day')
  })

  it('converts a forbidden or missing resource into a safe error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 'EVENT_NOT_FOUND',
      message: 'Crash 事件不存在',
    }), { status: 404, headers: { 'Content-Type': 'application/json' } })))

    await expect(crashApi.event('demo-app', 'event-404')).rejects.toMatchObject({
      status: 404,
      code: 'EVENT_NOT_FOUND',
      message: 'Crash 事件不存在',
    })
  })
})
