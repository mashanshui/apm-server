import { beforeEach, describe, expect, it, vi } from 'vitest'
import { appApi } from './appApi'

/** 调用方提交的部分字段与显式清空保持原语义。 */
describe('应用部分更新', () => {
  beforeEach(() => vi.restoreAllMocks())

  /** 省略字段不被客户端补空，null 与完整表单均按原输入发送。 */
  it.each([
    { name: '名称' },
    { description: '描述' },
    { description: null },
    { description: '' },
    {},
    { name: '名称', description: '描述' },
  ])('原样提交 PATCH %j', async (request) => {
    // 最小合成响应不包含任何凭据。
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ appId: 'app-1' })))
    await appApi.update('app-1', request)
    expect(fetchMock.mock.calls[0][1]?.method).toBe('PATCH')
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual(request)
  })
})
