import type { AnrEventDetail, AnrEventPage, AnrFilters, AnrOverview } from '../types/anr'
import { pathSegment, requestJson } from './http'

/** 构造与概览、列表一致的过滤参数。 */
export function anrQuery(filters: AnrFilters, cursor?: string): string {
  /** 精确时间窗口绑定服务端游标。 */
  const query = new URLSearchParams({ from: filters.from, to: filters.to, limit: '20' })
  if (filters.appVersion) query.set('appVersion', filters.appVersion)
  if (filters.source) query.set('source', filters.source)
  if (cursor) query.set('cursor', cursor)
  return query.toString()
}

/** 通过公共 HTTP 客户端读取应用级 ANR 数据。 */
export const anrApi = {
  /** 获取筛选范围的事件和去重设备数。 */
  overview(appId: string, filters: AnrFilters, signal?: AbortSignal): Promise<AnrOverview> {
    const query = new URLSearchParams(anrQuery(filters))
    query.delete('limit')
    return requestJson(`/api/v1/apps/${pathSegment(appId)}/anrs/overview?${query}`, { signal })
  },
  /** 获取已绑定条件的单页事件。 */
  events(appId: string, filters: AnrFilters, cursor?: string, signal?: AbortSignal): Promise<AnrEventPage> {
    return requestJson(`/api/v1/apps/${pathSegment(appId)}/anrs/events?${anrQuery(filters, cursor)}`, { signal })
  },
  /** 获取授权事件的原文和各分区还原状态。 */
  event(appId: string, eventId: string, signal?: AbortSignal): Promise<AnrEventDetail> {
    return requestJson(`/api/v1/apps/${pathSegment(appId)}/anrs/events/${pathSegment(eventId)}`, { signal })
  },
}
