import { describe, expect, it } from 'vitest'
import { ApiError } from '../api/http'
import { createJankCursorQuery, createJankQueryRegion } from './useJankQuery'

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

describe('卡顿查询基础设施', () => {
  it('取消旧请求并阻止旧响应覆盖最新数据', async () => {
    const region = createJankQueryRegion<string>()
    const first = deferred<string>()
    const second = deferred<string>()
    const signals: AbortSignal[] = []

    const firstRun = region.run((signal) => {
      signals.push(signal)
      return first.promise
    })
    const secondRun = region.run(() => second.promise)
    expect(signals[0].aborted).toBe(true)

    second.resolve('new')
    await secondRun
    first.resolve('old')
    await firstRun

    expect(region.data.value).toBe('new')
    expect(region.loading.value).toBe(false)
  })

  it('各区域错误互不覆盖', async () => {
    const overview = createJankQueryRegion<string>()
    const trend = createJankQueryRegion<string>()
    await Promise.all([
      overview.run(async () => 'overview-ok'),
      trend.run(async () => {
        throw new ApiError({ status: 500, message: '趋势失败' })
      }),
    ])
    expect(overview.data.value).toBe('overview-ok')
    expect(overview.error.value).toBeNull()
    expect(trend.error.value).toBe('趋势失败')
  })

  it('游标追加按稳定键去重并保留追加失败前的数据', async () => {
    const query = createJankCursorQuery<{ eventId: string }>((item) => item.eventId)
    await query.load(async () => ({
      items: [{ eventId: 'a' }, { eventId: 'b' }],
      nextCursor: 'cursor-2',
    }))
    await query.loadMore(async (cursor) => {
      expect(cursor).toBe('cursor-2')
      return { items: [{ eventId: 'b' }, { eventId: 'c' }], nextCursor: 'cursor-3' }
    })
    expect(query.items.value.map((item) => item.eventId)).toEqual(['a', 'b', 'c'])
    expect(query.nextCursor.value).toBe('cursor-3')

    await query.loadMore(async () => {
      throw new ApiError({ status: 500, message: '追加失败' })
    })
    expect(query.items.value.map((item) => item.eventId)).toEqual(['a', 'b', 'c'])
    expect(query.appendError.value).toBe('追加失败')
  })
  it('游标失效保留当前页，显式重新查询替换列表和时间窗', async () => {
    const query = createJankCursorQuery<{ eventId: string }>((item) => item.eventId)
    await query.load(async () => ({ items: [{ eventId: 'old' }], nextCursor: 'opaque', from: 'start', to: 'end' }))
    await query.loadMore(async (cursor) => {
      expect(cursor).toBe('opaque')
      throw new ApiError({ status: 400, code: 'INVALID_CURSOR', message: '游标无效' })
    })
    expect(query.items.value).toEqual([{ eventId: 'old' }])
    expect(query.cursorInvalid.value).toBe(true)
    expect(query.nextCursor.value).toBeNull()
    expect(query.range.value).toEqual({ from: 'start', to: 'end' })
    await query.load(async () => ({ items: [{ eventId: 'new' }], nextCursor: null, from: 'new-start', to: 'new-end' }))
    expect(query.items.value).toEqual([{ eventId: 'new' }])
    expect(query.cursorInvalid.value).toBe(false)
    expect(query.range.value).toEqual({ from: 'new-start', to: 'new-end' })
  })

  it.each(['QUERY_TIMEOUT', 'QUERY_RESOURCE_LIMIT'])('预算失败显示缩小范围提示并保留成功区域：%s', async (code) => {
    const failed = createJankQueryRegion<string>()
    const success = createJankQueryRegion<string>()
    await success.run(async () => 'success')
    await failed.run(async () => { throw new ApiError({ status: code === 'QUERY_TIMEOUT' ? 408 : 422, code, message: '查询失败' }) })
    expect(failed.error.value).toContain('缩小时间范围')
    expect(failed.data.value).toBeNull()
    expect(success.data.value).toBe('success')
  })

})
