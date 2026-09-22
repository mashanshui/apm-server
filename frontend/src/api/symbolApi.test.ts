import { beforeEach, describe, expect, it, vi } from 'vitest'
import { symbolApi } from './symbolApi'

describe('symbolApi', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  /** 列表筛选使用 URL 编码并保持应用路径隔离。 */
  it('lists symbols with an encoded buildId filter', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ appId: 'app/a', items: [], nextCursor: null }), { status: 200 }),
    )

    await symbolApi.list('app/a', { buildId: 'release 1' })

    expect(String(fetchMock.mock.calls[0][0])).toContain('/api/v1/apps/app%2Fa/symbols?buildId=release+1')
  })

  /** multipart 请求交给浏览器生成边界，不错误设置 JSON Content-Type。 */
  it('uploads mapping as multipart form data', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ symbolId: 'symbol-1' }), { status: 201 }),
    )
    const file = new File(['com.example.App -> a:'], 'mapping.txt', { type: 'text/plain' })

    await symbolApi.upload('app-1', 'release-1', file)

    const options = fetchMock.mock.calls[0][1] as RequestInit
    expect(options.body).toBeInstanceOf(FormData)
    expect((options.body as FormData).get('buildId')).toBe('release-1')
    expect((options.body as FormData).get('file')).toBe(file)
    expect(new Headers(options.headers).has('Content-Type')).toBe(false)
  })
})
