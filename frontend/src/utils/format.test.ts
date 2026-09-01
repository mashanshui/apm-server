import { describe, expect, it } from 'vitest'
import { formatPercent, formatPerThousand, metricValue, statusLabel } from './format'

describe('Crash display formatters', () => {
  it('does not turn an unavailable ratio into zero', () => {
    expect(formatPerThousand(null)).toBe('不可计算')
    expect(formatPercent(null)).toBe('不可计算')
    expect(metricValue(null, 'denominator_insufficient', 'perThousand')).toBe('不可计算')
    expect(metricValue(null, 'no_data', 'percent')).toBe('无数据')
  })

  it('keeps valid zero results visible', () => {
    expect(metricValue(0, 'ok', 'number')).toBe('0')
    expect(formatPerThousand(0)).toBe('0‰')
    expect(formatPercent(1)).toBe('100.0%')
  })

  it('labels the server statistical states', () => {
    expect(statusLabel('ok')).toBe('数据正常')
    expect(statusLabel('no_data')).toBe('无数据')
    expect(statusLabel('denominator_insufficient')).toBe('分母不足')
  })
})
