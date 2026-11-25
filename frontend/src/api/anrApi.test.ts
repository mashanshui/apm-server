import { afterEach, describe, expect, it, vi } from 'vitest'
import { anrApi } from './anrApi'

/** 验证 ANR 查询复用公共会话并转义路径与筛选。 */
describe('ANR API', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('编码应用、事件、时间和游标', async () => {
    /** 只返回请求行为所需的安全响应。 */
    const fetchMock = vi.fn().mockImplementation(async () => new Response(JSON.stringify({ events: [], nextCursor: null }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    await anrApi.events('app/a', { from: '2026-11-20T00:00:00Z', to: '2026-11-21T00:00:00Z',
      appVersion: '1+2', source: 'message_timeout' }, 'cursor+/')
    const [url, options] = fetchMock.mock.calls[0]
    expect(url).toContain('/apps/app%2Fa/anrs/events?')
    const query = new URL(url, 'http://localhost').searchParams
    expect(query.get('source')).toBe('message_timeout')
    expect(query.get('appVersion')).toBe('1+2')
    expect(query.get('cursor')).toBe('cursor+/')
    expect(options.credentials).toBe('include')
    await anrApi.event('app/a', 'event/b')
    expect(fetchMock.mock.calls[1][0]).toContain('/apps/app%2Fa/anrs/events/event%2Fb')
  })
})
