import { createMcpHandler, McpServer } from '@modelcontextprotocol/server';
import { z } from 'zod';
import { ApmClient, ApmError, digest, isRecord } from './apm.js';

/** 输入参数全集；工具只领取实际支持的字段。 */
const fields = {
  from: z.iso.datetime({ offset: true }).optional(), to: z.iso.datetime({ offset: true }).optional(),
  appVersion: z.string().max(100).optional(), channel: z.string().max(100).optional(),
  environment: z.string().max(100).optional(), osVersion: z.string().max(100).optional(),
  deviceModel: z.string().max(200).optional(), fingerprint: z.string().min(1).max(256).optional(),
  scene: z.string().max(100).optional(), algorithmVersion: z.string().max(100).optional(),
  processName: z.string().max(200).optional(), foreground: z.boolean().optional(),
  metric: z.string().max(64).optional(), dimension: z.string().max(64).optional(),
  interval: z.enum(['hour', 'day']).optional(), limit: z.number().int().min(1).max(100).optional(),
  cursor: z.string().max(4096).optional(), page: z.number().int().min(1).optional(),
  pageSize: z.number().int().min(1).max(100).optional(), sort: z.string().max(64).optional(),
  order: z.enum(['asc', 'desc']).optional(), manufacturer: z.string().max(100).optional(),
  sdkInt: z.number().int().min(1).max(100).optional(), dumpReason: z.string().max(100).optional(),
  anonymousDeviceId: z.string().max(256).optional(), signature: z.string().max(256).optional(),
  keyword: z.string().max(200).optional(), eventId: z.string().min(1).max(256),
  section: z.string().max(100).optional(), offset: z.number().int().min(0).optional(),
  chunkBytes: z.number().int().min(1).max(32_768).optional(),
  contentDigest: z.string().regex(/^[0-9a-f]{64}$/).optional()
} as const;

/** 19 个工具对应现有 19 个 Agent HTTP 查询入口。 */
const tools: ReadonlyArray<readonly [string, string, readonly (keyof typeof fields)[]]> = [
  ['get_application', '/application', []],
  ['get_crash_overview', '/crashes/overview', ['from','to','appVersion','channel','environment','osVersion','deviceModel','fingerprint']],
  ['get_crash_trend', '/crashes/trend', ['from','to','appVersion','channel','environment','osVersion','deviceModel','fingerprint','interval']],
  ['list_crash_issues', '/crashes/issues', ['from','to','appVersion','channel','environment','osVersion','deviceModel','fingerprint','limit','cursor']],
  ['list_crash_events', '/crashes/issues/{fingerprint}/events', ['fingerprint','from','to','appVersion','channel','environment','osVersion','deviceModel','limit','cursor']],
  ['get_crash_event', '/crashes/events/{eventId}', ['eventId','section','offset','chunkBytes','contentDigest']],
  ['get_jank_overview', '/janks/overview', ['from','to','appVersion','channel','environment','osVersion','deviceModel','scene','algorithmVersion']],
  ['get_jank_trend', '/janks/trend', ['from','to','appVersion','channel','environment','osVersion','deviceModel','scene','algorithmVersion','interval']],
  ['list_jank_issues', '/janks/issues', ['from','to','appVersion','channel','environment','osVersion','deviceModel','scene','algorithmVersion','limit','cursor']],
  ['list_jank_events', '/janks/issues/{fingerprint}/events', ['fingerprint','from','to','appVersion','channel','environment','osVersion','deviceModel','scene','algorithmVersion','limit','cursor']],
  ['get_jank_event', '/janks/events/{eventId}', ['eventId','section','offset','chunkBytes','contentDigest']],
  ['get_fps', '/jank-metrics/fps', ['from','to','appVersion','channel','environment','osVersion','deviceModel','scene','algorithmVersion']],
  ['get_suspension_rate', '/jank-metrics/suspension-rate', ['from','to','appVersion','channel','environment','osVersion','deviceModel','scene','algorithmVersion']],
  ['get_jank_metric_trend', '/jank-metrics/trend', ['from','to','metric','interval','appVersion','channel','environment','osVersion','deviceModel','scene','algorithmVersion']],
  ['get_jank_dimensions', '/jank-metrics/dimensions', ['from','to','metric','dimension','appVersion','channel','environment','osVersion','deviceModel','scene','algorithmVersion']],
  ['get_memory_summary', '/memory-metrics/summary', ['from','to','appVersion','osVersion','deviceModel','processName','scene','foreground']],
  ['get_memory_trend', '/memory-metrics/trend', ['from','to','metric','interval','appVersion','osVersion','deviceModel','processName','scene','foreground']],
  ['list_memory_leak_issues', '/memory-leaks/issues', ['from','to','appVersion','deviceModel','processName','scene','manufacturer','sdkInt','dumpReason','anonymousDeviceId','signature','keyword','page','pageSize','sort','order']],
  ['get_memory_leak_trend', '/memory-leaks/trend', ['from','to','appVersion','deviceModel','processName','scene','manufacturer','sdkInt','dumpReason','anonymousDeviceId','signature','keyword','interval']]
];

/** 大详情的顶层证据字段，默认只返回摘要。 */
const detailSections: Record<string, readonly string[]> = {
  get_crash_event: ['rawCrash', 'symbolicatedStackText'],
  get_jank_event: ['jank', 'analysis']
};

/** 统一证据包装，不推断后端未提供的算法或符号版本。 */
const outputSchema = z.object({
  data: z.record(z.string(), z.unknown()),
  query: z.record(z.string(), z.string()),
  evidence: z.record(z.string(), z.unknown()),
  retrievedAt: z.iso.datetime(), truncated: z.boolean(),
  continuation: z.object({ section: z.string(), offset: z.number().int().min(0),
    contentDigest: z.string() }).optional()
});

/** 每个 MCP 请求产生独立的工具闭包，Token 不写入进程全局状态。 */
export function createProtocolHandler(client: ApmClient) {
  return createMcpHandler(({ authInfo, requestInfo }) => {
    const server = new McpServer({ name: 'apm-query', version: '0.1.0' });
    const token = authInfo?.token;
    for (const [name, template, keys] of tools) {
      const shape: Record<string, z.ZodType> = {};
      for (const key of keys) shape[key] = fields[key];
      server.registerTool(name, {
        description: `只读 APM 查询；对应 ${template}。UTC 时间窗默认最近 24 小时、最长 31 天，返回后端原有统计单位及空值。`,
        inputSchema: z.strictObject(shape), outputSchema,
        annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true }
      }, async (input) => {
        try {
          if (!token) throw new ApmError('QUERY_TOKEN_INVALID', 401, false);
          const args = input as Record<string, unknown>;
          const { path, query } = prepare(template, args);
          const body = await client.get(path, token, query, requestInfo?.signal);
          const result = project(name, body, query, args);
          const serialized = JSON.stringify(result);
          const response = { content: [{ type: 'text' as const, text: serialized }], structuredContent: result };
          if (Buffer.byteLength(JSON.stringify(response)) > 256 * 1024) {
            throw new ApmError('RESULT_TOO_LARGE', 422, false);
          }
          return response;
        } catch (error) {
          const failure = error instanceof ApmError ? error : new ApmError('QUERY_INTERNAL_ERROR', 503, true);
          const result = { code: failure.code, retryable: failure.retryable,
            requestId: failure.requestId ?? null, retryAfter: failure.retryAfter ?? null };
          return { isError: true, content: [{ type: 'text' as const, text: JSON.stringify(result) }] };
        }
      });
    }
    return server;
  }, { responseMode: 'json', maxRequestBodySize: 64 * 1024 });
}

/** 规范化固定绝对时间窗，游标请求不得使用漂移的默认时间。 */
function prepare(template: string, args: Record<string, unknown>): { path: string; query: Record<string, string> } {
  let path = template;
  const query: Record<string, string> = {};
  for (const key of ['eventId', 'fingerprint']) {
    if (path.includes(`{${key}}`)) {
      const value = args[key];
      if (typeof value !== 'string' || !/^[A-Za-z0-9_.~-]+$/.test(value)) throw new ApmError('INVALID_FILTER', 400, false);
      path = path.replace(`{${key}}`, value);
    }
  }
  const hasTime = template !== '/application' && !template.includes('/events/{eventId}');
  if (hasTime) {
    if (args.cursor && (!args.from || !args.to)) throw new ApmError('INVALID_CURSOR', 400, false);
    const to = args.to ? Date.parse(String(args.to)) : Date.now();
    const from = args.from ? Date.parse(String(args.from)) : to - 24 * 60 * 60 * 1000;
    if (!Number.isFinite(from) || !Number.isFinite(to) || from >= to || to - from > 31 * 24 * 60 * 60 * 1000) {
      throw new ApmError('INVALID_TIME_RANGE', 400, false);
    }
    query.from = new Date(from).toISOString();
    query.to = new Date(to).toISOString();
  }
  for (const [key, value] of Object.entries(args)) {
    if (value !== undefined && !['from','to','eventId','section','offset','chunkBytes','contentDigest'].includes(key)
        && !(key === 'fingerprint' && template.includes('{fingerprint}'))) query[key] = String(value);
  }
  return { path, query };
}

/** 详情按 UTF-8 字节进行可重建分片，续读绑定当前内容 SHA-256。 */
function project(name: string, body: unknown, query: Record<string, string>, args: Record<string, unknown>) {
  const retrievedAt = new Date().toISOString();
  const evidence = isRecord(body) ? Object.fromEntries(
    ['appId','eventId','fingerprint','buildId','symbolFileId','symbolFileRevision','algorithmVersion']
      .filter((key) => key in body).map((key) => [key, body[key]])
  ) : {};
  const sections = detailSections[name];
  if (!sections) return { data: body, query, evidence, retrievedAt, truncated: false };
  if (!isRecord(body)) throw new ApmError('QUERY_BACKEND_INVALID_RESPONSE', 503, true);
  const selected = args.section;
  if (selected === undefined) {
    const summary = Object.fromEntries(Object.entries(body).filter(([key]) => !sections.includes(key)));
    if (name === 'get_jank_event' && isRecord(body.analysis)) {
      // 保留精确/估算时长与采样质量，庞大的树和字典仍按 section 续读。
      summary.analysisSummary = Object.fromEntries(
        ['exactMessageDurationNs','estimatedDurationNs','estimatedUnattributedDurationNs',
          'coveredDurationNs','uncoveredDurationNs','expectedSampleCount','parsedSampleCount',
          'missingSampleCount','algorithmVersion','warnings']
          .filter((key) => key in (body.analysis as Record<string, unknown>))
          .map((key) => [key, (body.analysis as Record<string, unknown>)[key]])
      );
    }
    return { data: { ...summary, availableSections: sections.filter((key) => body[key] != null) },
      query, evidence, retrievedAt, truncated: false };
  }
  if (typeof selected !== 'string' || !sections.includes(selected)) throw new ApmError('INVALID_SECTION', 400, false);
  const bytes = Buffer.from(JSON.stringify(body[selected] ?? null));
  const hash = digest(bytes);
  if (args.contentDigest !== undefined && args.contentDigest !== hash) throw new ApmError('EVIDENCE_CHANGED', 409, false);
  const offset = typeof args.offset === 'number' ? args.offset : 0;
  const size = typeof args.chunkBytes === 'number' ? args.chunkBytes : 16_384;
  if (offset > bytes.length) throw new ApmError('INVALID_OFFSET', 400, false);
  const end = Math.min(bytes.length, offset + size);
  const continuation = end < bytes.length ? { section: selected, offset: end, contentDigest: hash } : undefined;
  return { data: { section: selected, encoding: 'base64-json-utf8', content: bytes.subarray(offset, end).toString('base64'),
    offset, totalBytes: bytes.length, contentDigest: hash }, query, evidence, retrievedAt,
    truncated: continuation !== undefined, continuation };
}
