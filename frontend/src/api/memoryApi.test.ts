import { beforeEach, describe, expect, it, vi } from 'vitest'
import { buildMemoryQueryParams, memoryApi } from './memoryApi'

describe('memoryApi', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('构造内存筛选和趋势白名单参数', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ ok: true }), { status: 200 }))
    const params = buildMemoryQueryParams({ from: '2026-01-01T00:00:00Z', processName: 'demo', foreground: false })
    expect(params.toString()).toContain('foreground=false')
    expect(params.toString()).not.toContain('bit')
    await memoryApi.trend('app/a', 'pss', 'hour', { processName: 'demo' })
    expect(String(fetchMock.mock.calls[0][0])).toContain('/api/v1/apps/app%2Fa/memory-metrics/trend')
    expect(String(fetchMock.mock.calls[0][0])).toContain('metric=pss')
    expect(String(fetchMock.mock.calls[0][0])).toContain('interval=hour')
  })
})
