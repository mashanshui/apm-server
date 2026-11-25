import { describe, expect, it } from 'vitest'
import router from './index'

/** 列表和事件详情都支持受保护的直接地址。 */
describe('ANR 路由', () => {
  it('注册应用级列表与详情', () => {
    const routes = new Map(router.getRoutes().map(route => [String(route.name), route]))
    expect(routes.get('anr-list')?.path).toBe('/apps/:appId/anrs')
    expect(routes.get('anr-event-detail')?.path).toBe('/apps/:appId/anrs/events/:eventId')
    expect(routes.get('anr-list')?.meta).toMatchObject({ requiresAuth: true, appContext: true })
    expect(routes.get('anr-event-detail')?.meta).toMatchObject({ requiresAuth: true, appContext: true })
  })
})
