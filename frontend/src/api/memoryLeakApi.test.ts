import { beforeEach, describe, expect, it, vi } from 'vitest'
import { buildMemoryLeakQueryParams, memoryLeakApi } from './memoryLeakApi'

describe('memoryLeakApi', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('为问题和趋势端点分别使用参数白名单', async () => {
    // 每次请求都返回独立的 Response，避免第一个请求读取 body 后影响第二个请求。
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => (
      new Response(JSON.stringify({ status: 'ok' }), { status: 200 })
    ))
    const filters = { page: 2, pageSize: 50, sort: 'affectedDevices' as const, order: 'asc' as const, interval: '5m' as const, keyword: '<Activity>' }
    expect(buildMemoryLeakQueryParams(filters).toString()).toContain('page=2')
    expect(buildMemoryLeakQueryParams(filters).toString()).not.toContain('interval=')
    expect(buildMemoryLeakQueryParams(filters, 'trend').toString()).toContain('interval=5m')
    expect(buildMemoryLeakQueryParams(filters, 'trend').toString()).not.toContain('page=')
    await memoryLeakApi.issues('app/a', filters)
    await memoryLeakApi.trend('app/a', filters)
    expect(String(fetchMock.mock.calls[0][0])).toContain('/api/v1/apps/app%2Fa/memory-leaks/issues')
    expect(String(fetchMock.mock.calls[1][0])).toContain('/api/v1/apps/app%2Fa/memory-leaks/trend')
    expect(String(fetchMock.mock.calls[1][0])).toContain('interval=5m')
    expect(String(fetchMock.mock.calls[1][0])).not.toContain('page=')
  })
})
