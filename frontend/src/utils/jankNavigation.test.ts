import { describe, expect, it } from 'vitest'
import { jankAppSwitchTarget } from './jankNavigation'

describe('jankAppSwitchTarget', () => {
  it('指标页保留合法指标视图并规范挂起率组合', () => {
    const target = jankAppSwitchTarget('jank-metrics', 'next', {
      from: '2026-08-15T00:00:00Z', to: '2026-08-16T00:00:00Z', metric: 'suspension_rate', interval: 'hour', dimension: 'scene', scene: 'feed',
    }) as { name: string; query: Record<string, unknown> }
    expect(target.name).toBe('jank-metrics')
    expect(target.query.metric).toBe('suspension_rate')
    expect(target.query.interval).toBe('day')
    expect(target.query.dimension).toBe('deviceModel')
    expect(target.query.scene).toBeUndefined()
  })

  it('详情页返回目标应用问题列表并移除指纹和游标', () => {
    const target = jankAppSwitchTarget('jank-issue-events', 'next', {
      from: '2026-08-15T00:00:00Z', to: '2026-08-16T00:00:00Z', fingerprint: 'old', cursor: 'cursor', algorithmVersion: 'jank-v1',
    }) as { name: string; params: Record<string, unknown>; query: Record<string, unknown> }
    expect(target.name).toBe('jank-issues')
    expect(target.params.appId).toBe('next')
    expect(target.query.fingerprint).toBeUndefined()
    expect(target.query.cursor).toBeUndefined()
    expect(target.query.algorithmVersion).toBe('jank-v1')
  })

  it('内存页切换应用时保留筛选并移除不支持的位数参数', () => {
    const target = jankAppSwitchTarget('memory-metrics', 'next', {
      from: '2026-08-15T00:00:00Z', to: '2026-08-16T00:00:00Z', metric: 'vss', interval: 'day',
      percentile: 'p95', processName: 'worker', foreground: 'false', bitness: '64',
    }) as { name: string; params: Record<string, unknown>; query: Record<string, unknown> }
    expect(target.name).toBe('memory-metrics')
    expect(target.params.appId).toBe('next')
    expect(target.query.metric).toBe('vss')
    expect(target.query.percentile).toBe('p95')
    expect(target.query.foreground).toBe('false')
    expect(target.query.bitness).toBeUndefined()
  })

  it('内存泄漏页切换应用时保留筛选、分页和趋势状态', () => {
    const target = jankAppSwitchTarget('memory-leaks', 'next', {
      from: '2026-08-15T00:00:00Z', to: '2026-08-16T00:00:00Z', page: '2', pageSize: '50',
      trendMetric: 'affectedDevices', keyword: '<Activity>', unknown: 'drop',
    }) as { name: string; params: Record<string, unknown>; query: Record<string, unknown> }
    expect(target.name).toBe('memory-leaks')
    expect(target.params.appId).toBe('next')
    expect(target.query.page).toBe('2')
    expect(target.query.trendMetric).toBe('affectedDevices')
    expect(target.query.unknown).toBeUndefined()
  })
})
