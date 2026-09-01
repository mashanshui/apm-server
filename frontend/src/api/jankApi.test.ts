import { afterEach, describe, expect, it, vi } from 'vitest'
import { buildJankMetricQueryParams, buildJankQueryParams, jankApi } from './jankApi'

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

function jsonResponse(payload: unknown = {}): Response {
  return new Response(JSON.stringify(payload), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('jankApi', () => {
  it('仅编码白名单查询参数并忽略空值', () => {
    const issue = buildJankQueryParams({
      from: '2026-08-27T00:00:00Z',
      scene: 'checkout',
      algorithmVersion: 'jank-v1',
      channel: '',
      cursor: 'cursor/1',
      limit: 50,
    })
    expect(issue.get('scene')).toBe('checkout')
    expect(issue.get('algorithmVersion')).toBe('jank-v1')
    expect(issue.get('cursor')).toBe('cursor/1')
    expect(issue.get('channel')).toBeNull()

    const metric = buildJankMetricQueryParams({ scene: 'home', timeoutMs: 2_000 })
    expect(metric.get('scene')).toBe('home')
    expect(metric.get('timeoutMs')).toBe('2000')
    expect(metric.get('cursor')).toBeNull()
    expect(metric.get('fingerprint')).toBeNull()
  })

  it('安全编码应用、指纹和事件路径并只使用同源 Session', async () => {
    const fetchMock = vi.fn().mockImplementation(async () => jsonResponse({ events: [], nextCursor: null }))
    vi.stubGlobal('fetch', fetchMock)

    await jankApi.events('demo/app', 'fingerprint/a+b', { limit: 50 })
    await jankApi.event('demo/app', 'event/a b')

    expect(String(fetchMock.mock.calls[0][0])).toContain(
      '/api/v1/apps/demo%2Fapp/janks/issues/fingerprint%2Fa%2Bb/events?limit=50',
    )
    expect(String(fetchMock.mock.calls[1][0])).toContain(
      '/api/v1/apps/demo%2Fapp/janks/events/event%2Fa%20b',
    )
    const init = fetchMock.mock.calls[0][1] as RequestInit
    const headers = new Headers(init.headers)
    expect(init.credentials).toBe('include')
    expect(headers.has('X-App-Id')).toBe(false)
    expect(headers.has('X-User-App-Ids')).toBe(false)
    expect(headers.has('X-App-Key')).toBe(false)
  })

  it('为统一指标趋势传递合法组合并拒绝挂起率小时粒度', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ points: [] }))
    vi.stubGlobal('fetch', fetchMock)

    await jankApi.metricTrend('demo-app', 'fps', 'day', { scene: 'checkout' })
    const url = new URL(String(fetchMock.mock.calls[0][0]), 'http://localhost')
    expect(url.pathname).toBe('/api/v1/apps/demo-app/jank-metrics/trend')
    expect(url.searchParams.get('metric')).toBe('fps')
    expect(url.searchParams.get('interval')).toBe('day')
    expect(url.searchParams.get('scene')).toBe('checkout')

    expect(() => jankApi.metricTrend('demo-app', 'suspension_rate', 'hour', {})).toThrowError(
      expect.objectContaining({ code: 'INVALID_INTERVAL', status: 400 }),
    )
    expect(() => jankApi.dimensions('demo-app', 'suspension_rate', 'scene', {})).toThrowError(
      expect.objectContaining({ code: 'INVALID_DIMENSION', status: 400 }),
    )
  })

  it('将 AbortSignal 传给 fetch', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ issues: [] }))
    vi.stubGlobal('fetch', fetchMock)
    const controller = new AbortController()

    await jankApi.issues('demo-app', {}, controller.signal)

    expect((fetchMock.mock.calls[0][1] as RequestInit).signal).toBe(controller.signal)
  })

  it('401 时派发统一会话失效事件', async () => {
    const expired = vi.fn()
    window.addEventListener('apm:auth-expired', expired)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 'AUTH_REQUIRED',
      message: '登录状态已失效',
    }), { status: 401, headers: { 'Content-Type': 'application/json' } })))

    await expect(jankApi.overview('demo-app', {})).rejects.toMatchObject({
      status: 401,
      code: 'AUTH_REQUIRED',
    })
    expect(expired).toHaveBeenCalledTimes(1)
    window.removeEventListener('apm:auth-expired', expired)
  })
})
