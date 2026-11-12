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

    await symbolApi.upload('app-1', '1', 'release-1', file)

    const options = fetchMock.mock.calls[0][1] as RequestInit
    expect(options.body).toBeInstanceOf(FormData)
    expect((options.body as FormData).get('appVersion')).toBe('1')
    expect((options.body as FormData).get('buildId')).toBe('release-1')
    expect((options.body as FormData).get('file')).toBe(file)
    expect(new Headers(options.headers).has('Content-Type')).toBe(false)
  })
  /** 默认登记只带版本，SO 全批确认不带应用构建。 */
  it('keeps optional mapping build separate from native ELF identity', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response('{}', { status: 200 }))
    const file = new File(['fixture'], 'symbols.zip')
    await symbolApi.upload('app-1', '2.0', null, file)
    const mapping = fetchMock.mock.calls[0][1]?.body as FormData
    expect(mapping.get('appVersion')).toBe('2.0')
    expect(mapping.has('buildId')).toBe(false)
    const expected = [{ abi: 'arm64-v8a', elfBuildId: 'aabb', symbolId: null, revision: null }]
    await symbolApi.uploadNative('app-1', '2.0', file, { abi: 'arm64-v8a', elfBuildId: 'aabb' }, expected)
    const native = fetchMock.mock.calls[1][1]?.body as FormData
    expect(native.has('buildId')).toBe(false)
    expect(native.get('targetAbi')).toBe('arm64-v8a')
    expect(native.get('targetElfBuildId')).toBe('aabb')
    expect(JSON.parse(String(native.get('expectedEntries')))).toEqual(expected)
  })

})
