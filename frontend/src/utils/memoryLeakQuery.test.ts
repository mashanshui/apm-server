import { describe, expect, it } from 'vitest'
import { createDefaultMemoryLeakFilters, memoryLeakFiltersToQuery, parseMemoryLeakFilters, toMemoryLeakApiFilters } from './memoryLeakQuery'

describe('memoryLeakQuery', () => {
  it('恢复深链接筛选、分页排序和趋势状态，并对非法值回退', () => {
    const filters = parseMemoryLeakFilters({ page: '0', pageSize: '1000', sort: 'bad', interval: 'bad', trendMetric: 'affectedDevices', keyword: '<Activity>' })
    expect(filters.page).toBe(1)
    expect(filters.pageSize).toBe(100)
    expect(filters.sort).toBe('occurrences')
    expect(filters.interval).toBe('hour')
    expect(filters.trendMetric).toBe('affectedDevices')
    expect(filters.keyword).toBe('<Activity>')
  })

  it('生成筛选 URL 和 API 参数时保留有效零值的筛选文本', () => {
    const filters = { ...createDefaultMemoryLeakFilters(new Date('2026-01-02T00:00:00Z')), sdkInt: '0', page: 3, pageSize: 10 }
    const query = memoryLeakFiltersToQuery(filters)
    const api = toMemoryLeakApiFilters(filters)
    expect(query.sdkInt).toBe('0')
    expect(api.sdkInt).toBe('0')
    expect(query.page).toBe('3')
    expect(query.pageSize).toBe('10')
  })
})
