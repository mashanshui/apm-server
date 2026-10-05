import { ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { jankApi } from '../api/jankApi'
import { createDefaultJankFilters } from '../utils/jankQuery'
import { useJankIssues } from './useJankIssues'
import { useJankIssueEvents } from './useJankIssueEvents'

/** 真实 composable 使用首个后端响应时间窗，而不是续页时重新计算默认窗。 */
describe('卡顿续页时间窗', () => {
  it('Issue 与事件原样传递游标及完整响应时间', async () => {
    const filters = ref(createDefaultJankFilters(new Date('2026-10-04T00:00:00Z')))
    const issuesMock = vi.spyOn(jankApi, 'issues').mockResolvedValue({
      appId: 'app', from: '2026-10-01T00:00:00.123Z', to: '2026-10-02T00:00:00.456Z',
      issues: [], nextCursor: 'opaque+/=_', status: 'ok', dataSource: 'memory',
    })
    const query = useJankIssues(ref('app'), filters)
    await query.loadIssues()
    await query.loadMoreIssues()
    expect(issuesMock.mock.calls[1]?.[1]).toMatchObject({ from: '2026-10-01T00:00:00.123Z', to: '2026-10-02T00:00:00.456Z', cursor: 'opaque+/=_' })
    const eventsMock = vi.spyOn(jankApi, 'events').mockResolvedValue({
      appId: 'app', fingerprint: 'fp', from: '2026-10-01T00:00:00.123Z', to: '2026-10-02T00:00:00.456Z',
      events: [], nextCursor: 'opaque-event', status: 'ok', dataSource: 'memory',
    })
    const events = useJankIssueEvents(ref('app'), ref('fp'), filters)
    await events.load()
    await events.loadMore()
    expect(eventsMock.mock.calls[1]?.[2]).toMatchObject({ from: '2026-10-01T00:00:00.123Z', to: '2026-10-02T00:00:00.456Z', cursor: 'opaque-event' })
    vi.restoreAllMocks()
  })

  it('切换应用后忽略旧请求并替换列表', async () => {
    const app = ref('old')
    const filters = ref(createDefaultJankFilters())
    let resolveOld!: (value: Awaited<ReturnType<typeof jankApi.issues>>) => void
    const old = new Promise<Awaited<ReturnType<typeof jankApi.issues>>>((resolve) => { resolveOld = resolve })
    const mock = vi.spyOn(jankApi, 'issues').mockReturnValueOnce(old).mockResolvedValueOnce({ appId: 'new', from: filters.value.from, to: filters.value.to, issues: [], nextCursor: 'new-cursor', status: 'ok', dataSource: 'memory' })
    const query = useJankIssues(app, filters)
    const pending = query.loadIssues()
    app.value = 'new'
    await query.loadIssues()
    resolveOld({ appId: 'old', from: '', to: '', issues: [], nextCursor: 'old-cursor', status: 'ok', dataSource: 'memory' })
    await pending
    expect(query.nextCursor.value).toBe('new-cursor')
    expect(mock.mock.calls[0]?.[2]?.aborted).toBe(true)
    vi.restoreAllMocks()
  })
})
