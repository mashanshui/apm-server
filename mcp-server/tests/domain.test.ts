import assert from 'node:assert/strict';
import test from 'node:test';
import { Client, StreamableHTTPClientTransport } from '@modelcontextprotocol/client';
import { ApmClient } from '../src/apm.js';
import { createProtocolHandler } from '../src/server.js';

/** 用官方客户端对工具投影做领域契约验证。 */
async function withClient(reply: (path: string) => unknown, run: (client: Client) => Promise<void>): Promise<void> {
  const backend = new ApmClient('http://backend.test', async (url) => {
    const path = new URL(url instanceof Request ? url.url : String(url)).pathname;
    return new Response(JSON.stringify(reply(path)));
  });
  const handler = createProtocolHandler(backend);
  const transport = new StreamableHTTPClientTransport(new URL('http://test.local/mcp'), {
    fetch: (url, init) => handler.fetch(new Request(url, init), {
      authInfo: { token: 'test', clientId: 'app-a', scopes: ['apm:read'] }
    })
  });
  const client = new Client({ name: 'domain-test', version: '0.1.0' });
  try {
    await client.connect(transport);
    await run(client);
  } finally {
    await client.close();
    await handler.close();
  }
}

/** Crash 的精确统计、页游标与符号化状态保持后端原值。 */
test('crash stats, pagination and symbolication fields survive projection', async () => {
  await withClient((path) => {
    if (path.endsWith('/overview')) return { appId: 'app-a',
      stats: { crashEvents: 0, crashRatePer1000Sessions: null, status: 'denominator_insufficient' } };
    if (path.endsWith('/issues')) return { appId: 'app-a',
      issues: [{ fingerprint: 'fp', eventCount: 2, groupingKind: 'temporary', buildId: 'build-1' }], nextCursor: 'opaque-next' };
    return { appId: 'app-a', eventId: 'e1', symbolicationStatus: 'symbolicated', crashKind: 'jvm', fatal: true, groupingKind: 'resolved',
      symbolFileId: 'original-symbol', symbolFileRevision: 3, symbolFileSha256: 'a'.repeat(64), processedAt: '2026-11-05T00:00:00Z', stack: { format: 'retrace_text', content: 'mapped stack' } };
  }, async (client) => {
    const overview = await client.callTool({ name: 'get_crash_overview', arguments: {} });
    assert.deepEqual((overview.structuredContent as { data: { stats: unknown } }).data.stats,
      { crashEvents: 0, crashRatePer1000Sessions: null, status: 'denominator_insufficient' });
    const issues = await client.callTool({ name: 'list_crash_issues', arguments: {} });
    assert.equal((issues.structuredContent as { data: { nextCursor: string } }).data.nextCursor, 'opaque-next');
    const detail = await client.callTool({ name: 'get_crash_event', arguments: { eventId: 'e1' } });
    const data = (detail.structuredContent as { data: Record<string, unknown> }).data;
    assert.equal(data.symbolicationStatus, 'symbolicated');
    assert.equal(data.symbolFileRevision, 3);
    assert.equal(data.symbolFileId, 'original-symbol');
    assert.equal(data.symbolFileSha256, 'a'.repeat(64));
    assert.equal(data.stackFormat, 'retrace_text');
    assert.equal('stack' in data, false);
    assert.deepEqual(data.availableSections, ['stack']);
  });
});

/** 旧片段在请求上游前明确拒绝，旧格式不能被自动转换。 */
test('crash rejects removed sections and unsupported backend contracts', async () => {
  let calls = 0;
  await withClient(() => { calls += 1; return {}; }, async (client) => {
    for (const section of ['rawCrash', 'symbolicatedStackText', 'unknown']) {
      const result = await client.callTool({ name: 'get_crash_event', arguments: { eventId: 'e1', section } });
      assert.equal(result.isError, true);
      assert.match(JSON.stringify(result.content), /INVALID_SECTION/);
    }
  });
  assert.equal(calls, 0);
  for (const reply of [
    { appId: 'app-a', eventId: 'e1', symbolicationStatus: 'raw_only', rawCrash: {} },
    { appId: 'app-a', eventId: 'e1', crashKind: 'jvm', fatal: true, symbolicationStatus: 'pending', groupingKind: 'temporary', stack: { format: 'retrace_text', content: 'wrong format' } }
  ]) {
    await withClient(() => reply, async (client) => {
      const result = await client.callTool({ name: 'get_crash_event', arguments: { eventId: 'e1' } });
      assert.equal(result.isError, true);
      assert.match(JSON.stringify(result.content), /QUERY_BACKEND_INVALID_RESPONSE/);
      assert.equal(result.structuredContent, undefined);
    });
  }
});

/** 算法版本、FPS 百分位方向、挂起率单位及内存零/null 直接沿用领域结果。 */
test('jank and memory metrics retain quality, units, versions and missing values', async () => {
  await withClient((path) => {
    if (path.endsWith('/fps')) return { appId: 'app-a', metrics: [
      { algorithmVersion: 'fps-v1', p50Fps: 55, p90Fps: 40, p99Fps: 20, status: 'ok' },
      { algorithmVersion: 'fps-v2', p50Fps: 58, p90Fps: 45, p99Fps: 30, status: 'ok' }] };
    if (path.endsWith('/suspension-rate')) return { appId: 'app-a', metrics: [
      { algorithmVersion: 'suspension-v1', suspensionSecondsPerForegroundHour: 120 }], status: 'ok' };
    return { appId: 'app-a', pss: { sampleCount: 1, averageBytes: 0 },
      vss: { sampleCount: 0, averageBytes: null }, javaHeap: { sampleCount: 1, averageBytes: 42 },
      status: 'ok' };
  }, async (client) => {
    const fps = (await client.callTool({ name: 'get_fps', arguments: {} })).structuredContent as
      { data: { metrics: Array<{ algorithmVersion: string; p99Fps: number }> } };
    assert.deepEqual(fps.data.metrics.map((item) => item.algorithmVersion), ['fps-v1', 'fps-v2']);
    assert.equal(fps.data.metrics[0]?.p99Fps, 20);
    const suspension = (await client.callTool({ name: 'get_suspension_rate', arguments: {} })).structuredContent as
      { data: { metrics: Array<{ suspensionSecondsPerForegroundHour: number }> } };
    assert.equal(suspension.data.metrics[0]?.suspensionSecondsPerForegroundHour, 120);
    const memory = (await client.callTool({ name: 'get_memory_summary', arguments: {} })).structuredContent as
      { data: { pss: { averageBytes: number }; vss: { averageBytes: null } } };
    assert.equal(memory.data.pss.averageBytes, 0);
    assert.equal(memory.data.vss.averageBytes, null);
  });
});

/** 大调用树与帧字典分片可无损拼回，不能产出悬空引用。 */
test('jank analysis chunks reconstruct the complete dictionary and call tree', async () => {
  const analysis = { exactMessageDurationNs: 1_000_000, estimatedDurationNs: 900_000,
    expectedSampleCount: 3, parsedSampleCount: 2, missingSampleCount: 1,
    callTree: [{ nodeId: 'root', frameId: 'f1', children: ['leaf'] }],
    stackDictionary: { f1: [{ className: 'com.example.Frame', methodName: 'run' }] } };
  await withClient(() => ({ appId: 'app-a', eventId: 'e1', jank: { samples: [] }, analysis }),
    async (client) => {
      const chunks: Buffer[] = [];
      let offset = 0;
      let contentDigest: string | undefined;
      while (true) {
        const result = await client.callTool({ name: 'get_jank_event',
          arguments: { eventId: 'e1', section: 'analysis', offset, chunkBytes: 40, contentDigest } });
        assert.equal(result.isError, undefined);
        const projected = result.structuredContent as {
          data: { content: string };
          continuation?: { offset: number; contentDigest: string };
        };
        chunks.push(Buffer.from(projected.data.content, 'base64'));
        if (!projected.continuation) break;
        offset = projected.continuation.offset;
        contentDigest = projected.continuation.contentDigest;
      }
      assert.deepEqual(JSON.parse(Buffer.concat(chunks).toString('utf8')), analysis);
    });
});

/** SDK 内存异常问题的现有引用链及无数据状态按原样返回。 */
test('memory leak issue references and empty state remain distinguishable', async () => {
  await withClient((path) => path.endsWith('/issues')
    ? { appId: 'app-a', status: 'ok', items: [{ signature: 'sig-1',
      referenceChain: [{ from: 'Activity', to: 'View' }] }] }
    : { appId: 'app-a', status: 'no_data', points: [] },
  async (client) => {
    const issues = (await client.callTool({ name: 'list_memory_leak_issues', arguments: {} })).structuredContent as
      { data: { items: Array<{ referenceChain: unknown[] }> } };
    assert.deepEqual(issues.data.items[0]?.referenceChain, [{ from: 'Activity', to: 'View' }]);
    const trend = (await client.callTool({ name: 'get_memory_leak_trend', arguments: {} })).structuredContent as
      { data: { status: string; points: unknown[] } };
    assert.equal(trend.data.status, 'no_data');
    assert.deepEqual(trend.data.points, []);
  });
});

/** 内存进程筛选只进入现有查询参数，不允许客户端注入应用身份或上游地址。 */
test('memory process filter is forwarded and unknown tool inputs are rejected', async () => {
  const requests: URL[] = [];
  const backend = new ApmClient('http://backend.test', async (url) => {
    requests.push(new URL(url instanceof Request ? url.url : String(url)));
    return new Response(JSON.stringify({ appId: 'app-a', status: 'no_data', pss: null }));
  });
  const handler = createProtocolHandler(backend);
  const transport = new StreamableHTTPClientTransport(new URL('http://test.local/mcp'), {
    fetch: (url, init) => handler.fetch(new Request(url, init), {
      authInfo: { token: 'test', clientId: 'app-a', scopes: ['apm:read'] }
    })
  });
  const client = new Client({ name: 'domain-test', version: '0.1.0' });
  try {
    await client.connect(transport);
    const filtered = await client.callTool({ name: 'get_memory_summary',
      arguments: { processName: 'com.example:worker', foreground: false } });
    assert.equal(filtered.isError, undefined);
    assert.equal(requests.length, 1);
    assert.equal(requests[0]?.searchParams.get('processName'), 'com.example:worker');
    assert.equal(requests[0]?.searchParams.get('foreground'), 'false');
    const injected = await client.callTool({ name: 'get_memory_summary',
      arguments: { appId: 'app-b', backendUrl: 'http://other.test' } });
    assert.equal(injected.isError, true);
    assert.equal(requests.length, 1);
  } finally {
    await client.close();
    await handler.close();
  }
});

/** UTF-8 片段完整恢复原始链及多候选；后台切换格式后不能续读旧摘要。 */
test('crash chunks preserve unicode and reject pending-to-success continuation', async () => {
  const raw = {format:'raw_chain',content:{kind:'jvm',fatal:true,throwableChain:[{type:'Exception',message:'完整中文🌧️',frames:[{className:'a',methodName:'b',fileName:'源.java',lineNumber:1,applicationFrame:true}]}]}};
  const mapped = {format:'retrace_text',content:'Exception: 完整中文🌧️\n at com.example.Main.first(Main.java:1)\n <OR> at com.example.Main.second(Main.java:2)'};
  let stack: unknown = raw;
  await withClient(() => ({appId:'app-a',eventId:'e1',crashKind:'jvm',fatal:true,stack,
    symbolicationStatus:stack===raw?'pending':'symbolicated',groupingKind:stack===raw?'temporary':'resolved',
    symbolFileId:stack===raw?null:'fixed',symbolFileRevision:stack===raw?null:1,symbolFileSha256:stack===raw?null:'a'.repeat(64),processedAt:stack===raw?null:'2026-11-05T00:00:00Z'}), async client => {
    for (const value of [raw,mapped]) {
      stack=value;
      let offset=0;
      let contentDigest: string | undefined;
      const chunks: Buffer[]=[];
      while (true) {
        const result=await client.callTool({name:'get_crash_event',arguments:{eventId:'e1',section:'stack',offset,chunkBytes:7,...(contentDigest?{contentDigest}:{})}});
        assert.notEqual(result.isError,true);
        const data=result.structuredContent as {data:{content:string;contentDigest:string};continuation?:{offset:number}};
        chunks.push(Buffer.from(data.data.content,'base64'));contentDigest=data.data.contentDigest;
        if (!data.continuation) break;
        offset=data.continuation.offset;
      }
      assert.deepEqual(JSON.parse(Buffer.concat(chunks).toString('utf8')),value);
      if (value===raw) {
        stack=mapped;
        const changed=await client.callTool({name:'get_crash_event',arguments:{eventId:'e1',section:'stack',offset:7,contentDigest}});
        assert.equal(changed.isError,true);assert.match(JSON.stringify(changed.content),/EVIDENCE_CHANGED/);
      }
    }
  });
});
