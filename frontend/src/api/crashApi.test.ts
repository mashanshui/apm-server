import { afterEach, describe, expect, it, vi } from 'vitest'
import { buildQueryParams, crashApi, requireCurrentCrash } from './crashApi'

afterEach(() => {
  vi.restoreAllMocks()
})

describe('crashApi', () => {
  it('builds supported query parameters and omits empty values', () => {
    const params = buildQueryParams({
      from: '2026-08-19T00:00:00Z',
      to: '2026-08-19T01:00:00Z',
      appVersion: '3.2.0',
      channel: '',
      limit: 50,
      timeoutMs: 2000,
    })

    expect(params.toString()).toContain('from=2026-08-19T00%3A00%3A00Z')
    expect(params.get('appVersion')).toBe('3.2.0')
    expect(params.get('channel')).toBeNull()
    expect(params.get('limit')).toBe('50')
  })

  it('scopes requests to the app without sending an ingest key', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      appId: 'demo-app',
      from: '2026-08-19T00:00:00Z',
      to: '2026-08-19T01:00:00Z',
      stats: {
        startedSessions: 1,
        crashEvents: 0,
        crashedSessions: 0,
        affectedDevices: 0,
        crashRatePer1000Sessions: 0,
        crashFreeSessionRate: 1,
        status: 'ok',
      },
      dataSource: 'memory',
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)

    await crashApi.overview('demo/app', { limit: 50 })

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/api/v1/apps/demo%2Fapp/crashes/overview?limit=50')
    expect((init.headers as Record<string, string>)['X-App-Id']).toBeUndefined()
    expect((init.headers as Record<string, string>)['X-App-Key']).toBeUndefined()
    expect(init.credentials).toBe('include')
  })

  it('passes the trend interval as a dedicated query parameter', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      appId: 'demo-app',
      from: '2026-08-19T00:00:00Z',
      to: '2026-08-19T01:00:00Z',
      interval: 'day',
      points: [],
      dataSource: 'memory',
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)

    await crashApi.trend('demo-app', {}, 'day')

    expect(String(fetchMock.mock.calls[0][0])).toContain('interval=day')
  })

  it('converts a forbidden or missing resource into a safe error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 'EVENT_NOT_FOUND',
      message: 'Crash 事件不存在',
    }), { status: 404, headers: { 'Content-Type': 'application/json' } })))

    await expect(crashApi.event('demo-app', 'event-404')).rejects.toMatchObject({
      status: 404,
      code: 'EVENT_NOT_FOUND',
      message: 'Crash 事件不存在',
    })
  })
  /** 不转换旧详情或无实际来源的成功结果。 */
  it.each([
    {rawCrash:{},symbolicationStatus:'raw_only'},
    {symbolicationStatus:'symbolicated',groupingKind:'resolved',crashKind:'jvm',fatal:true,stack:{format:'retrace_text',content:'a'}},
    {symbolicationStatus:'pending',groupingKind:'temporary',crashKind:'jvm',fatal:true,stack:{format:'retrace_text',content:'a'}},
  ])('rejects unsupported detail contracts', value => {
    expect(()=>requireCurrentCrash(value as never)).toThrow('协议错误')
  })

  /** 诊断允许空帧和数值零，但不接受未知状态、格式或重复线程身份。 */
  it('validates current diagnostics without losing zero values', () => {
    const detail = {
      symbolicationStatus: 'pending', groupingKind: 'temporary', crashKind: 'jvm', fatal: true,
      stack: { format: 'raw_chain', content: { kind: 'jvm', fatal: true, throwableChain: [{ type: 'E', frames: [] }] } },
      diagnostics: {
        crashedThread: { status: 'ok', truncated: false, javaId: '1', name: 'same', state: 'RUNNABLE', systemTid: 42 },
        otherThreads: { status: 'ok', truncated: false, observedCount: 2, omittedCount: 0, threads: [
          { javaId: '2', name: 'same', state: 'WAITING', priority: 5, daemon: true, mainThread: true,
            truncated: false, symbolicationStatus: 'not_applicable', stack: { format: 'raw_frames', content: [] } },
          { javaId: '3', name: 'same', state: 'WAITING', priority: 5, daemon: false, mainThread: false,
            truncated: false, symbolicationStatus: 'failed', symbolicationReason: 'timeout',
            stack: { format: 'raw_frames', content: [{ className: 'A', methodName: 'run', lineNumber: 0 }] } },
        ] },
        logcat: { status: 'ok', truncated: false, main: { status: 'ok', truncated: false, lines: [] } },
        memory: { status: 'ok', truncated: false, javaHeapUsedBytes: 0 },
        openFiles: { status: 'ok', truncated: false, count: 0, entries: [] },
        process: { status: 'ok', truncated: false, pid: 42, foreground: false },
        device: { status: 'ok', truncated: false, apiLevel: 29, abis: [] },
      },
    }
    expect(requireCurrentCrash(detail as never).diagnostics?.memory?.javaHeapUsedBytes).toBe(0)
    expect(requireCurrentCrash({ ...detail, diagnostics: null } as never).diagnostics).toBeNull()
    expect(() => requireCurrentCrash({ ...detail, diagnostics: { ...detail.diagnostics, memory: { status: 'unknown', truncated: false } } } as never)).toThrow('协议错误')
    expect(() => requireCurrentCrash({ ...detail, diagnostics: { ...detail.diagnostics, otherThreads: {
      ...detail.diagnostics.otherThreads, threads: [{ ...detail.diagnostics.otherThreads.threads[0], stack: { format: 'future', content: [] } }],
    } } } as never)).toThrow('协议错误')
    expect(() => requireCurrentCrash({ ...detail, diagnostics: { ...detail.diagnostics, otherThreads: {
      ...detail.diagnostics.otherThreads, threads: [{ ...detail.diagnostics.otherThreads.threads[0], javaId: '1' }],
    } } } as never)).toThrow('协议错误')
    expect(() => requireCurrentCrash({ ...detail, diagnostics: { ...detail.diagnostics, openFiles: { status: 'ok', truncated: false, count: -1, entries: [] } } } as never)).toThrow('协议错误')
  })

})
