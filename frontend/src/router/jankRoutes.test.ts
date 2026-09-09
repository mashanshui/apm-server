import { describe, expect, it } from 'vitest'
import router from './index'

describe('卡顿路由', () => {
  it('注册卡顿与内存受保护且支持 history 深层链接的应用路由', () => {
    const routes = new Map(router.getRoutes().map((route) => [String(route.name), route]))
    expect(routes.get('jank-metrics')?.path).toBe('/apps/:appId/jank-metrics')
    expect(routes.get('jank-issues')?.path).toBe('/apps/:appId/janks')
    expect(routes.get('jank-issue-events')?.path).toContain(':fingerprint')
    expect(routes.get('jank-event-detail')?.path).toContain(':eventId')
    expect(routes.get('memory-metrics')?.path).toBe('/apps/:appId/memory-metrics')
    expect(routes.get('memory-metrics')?.meta).toMatchObject({ requiresAuth: true, appContext: true })
    for (const name of ['jank-metrics', 'jank-issues', 'jank-issue-events', 'jank-event-detail']) {
      expect(routes.get(name)?.meta).toMatchObject({ requiresAuth: true, appContext: true })
    }
  })
})
