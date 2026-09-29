import { createHash } from 'node:crypto';

/** 后端错误只保留可供调用方决策的字段，绝不转发任意异常文本。 */
export class ApmError extends Error {
  constructor(
    public readonly code: string,
    public readonly status: number,
    public readonly retryable: boolean,
    public readonly requestId?: string,
    public readonly retryAfter?: string
  ) {
    super(code);
  }
}

/** 可信配置中的固定后端地址；工具输入无法覆盖协议、主机或前缀。 */
export class ApmClient {
  private readonly origin: URL;

  constructor(origin: string, private readonly fetcher: typeof fetch = fetch) {
    this.origin = new URL(origin);
    if (!['http:', 'https:'].includes(this.origin.protocol) || this.origin.username || this.origin.password
        || this.origin.pathname !== '/' || this.origin.search || this.origin.hash) {
      throw new Error('APM_BACKEND_ORIGIN 必须是没有路径和凭据的 HTTP(S) origin');
    }
  }

  /** 固定前缀 GET；每次查询由 Java 再次认证，禁止重定向和自动重试。 */
  async get(path: string, token: string, query: Record<string, string> = {}, signal?: AbortSignal): Promise<unknown> {
    if (!/^\/[a-z0-9/-]+(?:\/[A-Za-z0-9_.~-]+)*$/.test(path) || path.includes('..')) {
      throw new ApmError('INVALID_PATH', 400, false);
    }
    const url = new URL(`/api/agent/v1${path}`, this.origin);
    for (const [key, value] of Object.entries(query)) url.searchParams.set(key, value);
    const timeout = AbortSignal.timeout(6_000);
    const combined = signal ? AbortSignal.any([signal, timeout]) : timeout;
    let response: Response;
    try {
      response = await this.fetcher(url, {
        method: 'GET', redirect: 'error', signal: combined,
        headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' }
      });
    } catch (error) {
      if (combined.aborted) throw new ApmError('QUERY_TIMEOUT', 408, true);
      throw new ApmError('QUERY_BACKEND_UNAVAILABLE', 503, true);
    }
    let bytes: Uint8Array;
    try {
      bytes = await readBounded(response, 8 * 1024 * 1024);
    } catch (error) {
      if (combined.aborted) throw new ApmError('QUERY_TIMEOUT', 408, true);
      throw error;
    }
    let body: unknown;
    try {
      body = JSON.parse(new TextDecoder().decode(bytes));
    } catch {
      throw new ApmError('QUERY_BACKEND_INVALID_RESPONSE', 503, true);
    }
    if (!response.ok) {
      const parsed = isRecord(body) ? body : {};
      const code = typeof parsed.code === 'string' ? parsed.code : `HTTP_${response.status}`;
      const requestId = typeof parsed.requestId === 'string' ? parsed.requestId : undefined;
      throw new ApmError(code, response.status, response.status === 408 || response.status === 429 || response.status === 503,
        requestId, response.headers.get('retry-after') ?? undefined);
    }
    return body;
  }
}

/** 限制响应流总字节数，超过时取消读取并显式失败。 */
async function readBounded(response: Response, maxBytes: number): Promise<Uint8Array> {
  if (!response.body) throw new ApmError('QUERY_BACKEND_INVALID_RESPONSE', 503, true);
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const next = await reader.read();
      if (next.done) break;
      size += next.value.byteLength;
      if (size > maxBytes) throw new ApmError('QUERY_BACKEND_RESPONSE_TOO_LARGE', 422, false);
      chunks.push(next.value);
    }
  } catch (error) {
    await reader.cancel().catch(() => undefined);
    if (error instanceof ApmError) throw error;
    throw new ApmError('QUERY_BACKEND_UNAVAILABLE', 503, true);
  } finally {
    reader.releaseLock();
  }
  return Buffer.concat(chunks, size);
}

/** 只接受普通 JSON 对象。 */
export function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

/** 详情续读时验证当前内容的稳定摘要。 */
export function digest(value: Uint8Array): string {
  return createHash('sha256').update(value).digest('hex');
}
