import { describe, expect, it } from 'vitest'
import { createDefaultMemoryMetricFilters, memoryMetricFiltersToQuery, memoryMetricQueryWarning, parseMemoryMetricFilters, toMemoryApiFilters } from './memoryQuery'

describe('memoryQuery', () => {
  it('从 URL 恢复指标、前后台和分位数，非法值回退默认值', () => {
    const filters = parseMemoryMetricFilters({ metric: 'invalid', interval: 'day', foreground: 'false', percentile: 'p95' })
    expect(filters.metric).toBe('pss')
    expect(filters.interval).toBe('day')
    expect(filters.foreground).toBe('false')
    expect(filters.percentile).toBe('p95')
  })

  it('将前后台筛选转换为 API 布尔值且不生成 32/64 位参数', () => {
    const filters = { ...createDefaultMemoryMetricFilters(new Date('2026-01-02T00:00:00Z')), foreground: 'true' as const, processName: 'worker' }
    const query = memoryMetricFiltersToQuery(filters)
    const api = toMemoryApiFilters(filters)
    expect(query.foreground).toBe('true')
    expect(api.foreground).toBe(true)
    expect(Object.keys(query).some((key) => /bit|arch/i.test(key))).toBe(false)
  })

  it('对非法地址参数回退默认时间并给出修正提示', () => {
    const filters = parseMemoryMetricFilters({ from: 'not-a-time', bitness: '64', metric: 'fd' })
    expect(filters.metric).toBe('pss')
    expect(filters.from).not.toBe('not-a-time')
    expect(memoryMetricQueryWarning({ from: 'not-a-time' })).toContain('from')
    expect(memoryMetricQueryWarning({ bitness: '64' })).toContain('bitness')
  })
})
