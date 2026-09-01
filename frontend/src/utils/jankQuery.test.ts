import { describe, expect, it } from 'vitest'
import {
  createDefaultJankFilters,
  createDefaultJankMetricFilters,
  jankFiltersToQuery,
  jankMetricFiltersToQuery,
  normalizeJankMetricFilters,
  parseJankFilters,
  parseJankMetricFilters,
  toJankApiFilters,
  toJankMetricApiFilters,
} from './jankQuery'

describe('卡顿 URL 查询状态', () => {
  it('公共筛选完整往返并忽略空参数', () => {
    const query = {
      from: '2026-08-27T00:00:00Z',
      to: '2026-08-27T12:00:00Z',
      appVersion: '3.2.0',
      channel: '  ',
      scene: 'checkout',
      algorithmVersion: 'jank-v1',
      fingerprint: 'fp/a',
      interval: 'day',
    }
    const filters = parseJankFilters(query)
    expect(filters.channel).toBe('')
    expect(filters.interval).toBe('day')
    expect(jankFiltersToQuery(filters)).toMatchObject({
      from: query.from,
      to: query.to,
      appVersion: '3.2.0',
      scene: 'checkout',
      algorithmVersion: 'jank-v1',
      fingerprint: 'fp/a',
      interval: 'day',
    })
    expect(jankFiltersToQuery(filters)).not.toHaveProperty('channel')
    expect(toJankApiFilters(filters, 'next/1')).toMatchObject({
      limit: 50,
      timeoutMs: 2_000,
      cursor: 'next/1',
      fingerprint: 'fp/a',
    })
  })

  it('默认查询最近 24 小时且使用小时粒度', () => {
    const defaults = createDefaultJankFilters(new Date('2026-08-27T12:00:00.000Z'))
    expect(defaults.from).toBe('2026-08-26T12:00:00.000Z')
    expect(defaults.to).toBe('2026-08-27T12:00:00.000Z')
    expect(defaults.interval).toBe('hour')
  })

  it('指标白名单回退并将挂起率固定为 UTC day', () => {
    const parsed = parseJankMetricFilters({
      metric: 'suspension_rate',
      interval: 'hour',
      dimension: 'scene',
      scene: 'checkout',
      from: '',
      to: '',
    }, new Date('2026-08-27T12:00:00.000Z'))
    expect(parsed.metric).toBe('suspension_rate')
    expect(parsed.interval).toBe('day')
    expect(parsed.dimension).toBe('deviceModel')
    expect(parsed.scene).toBe('')

    const invalid = parseJankMetricFilters({ metric: 'cpu', interval: 'week', dimension: 'sql' })
    expect(invalid.metric).toBe('fps')
    expect(invalid.interval).toBe('hour')
    expect(invalid.dimension).toBe('scene')
  })

  it('指标筛选序列化前执行组合规范化', () => {
    const filters = {
      ...createDefaultJankMetricFilters(new Date('2026-08-27T12:00:00.000Z')),
      metric: 'suspension_rate' as const,
      interval: 'hour' as const,
      dimension: 'scene' as const,
      scene: 'checkout',
    }
    expect(normalizeJankMetricFilters(filters)).toMatchObject({
      interval: 'day',
      dimension: 'deviceModel',
      scene: '',
    })
    expect(jankMetricFiltersToQuery(filters)).toMatchObject({
      metric: 'suspension_rate',
      interval: 'day',
      dimension: 'deviceModel',
    })
    expect(toJankMetricApiFilters(filters)).not.toHaveProperty('scene')
  })
})
