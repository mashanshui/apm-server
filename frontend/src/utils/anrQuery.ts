import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import type { AnrFilters } from '../types/anr'

/** 将合法筛选保存在 URL，默认固定为最近 24 小时。 */
export function parseAnrFilters(query: LocationQuery, now = Date.now()): AnrFilters {
  /** 数组和非法时间不应覆盖安全默认值。 */
  const value = (key: string) => typeof query[key] === 'string' ? query[key] as string : ''
  const parseTime = (text: string, fallback: number) => {
    const timestamp = Date.parse(text)
    return new Date(Number.isFinite(timestamp) ? timestamp : fallback).toISOString()
  }
  const source = value('source')
  return {
    from: parseTime(value('from'), now - 24 * 60 * 60 * 1000),
    to: parseTime(value('to'), now),
    appVersion: value('appVersion').trim(),
    source: source === 'message_timeout' || source === 'process_error_state' ? source : '',
  }
}

/** 仅输出服务端支持且非空的筛选。 */
export function anrFiltersToQuery(filters: AnrFilters): LocationQueryRaw {
  return { from: filters.from, to: filters.to, ...(filters.appVersion ? { appVersion: filters.appVersion } : {}),
    ...(filters.source ? { source: filters.source } : {}) }
}

/** 使用明确中文解释 Matrix 的两个判定来源。 */
export function anrSourceLabel(source: string | null | undefined): string {
  if (source === 'message_timeout') return '主线程消息超时'
  if (source === 'process_error_state') return '系统进程错误状态'
  return '未知来源'
}
