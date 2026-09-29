import { createServer } from 'node:http';
import { hostHeaderValidation, originValidation, toNodeHandler } from '@modelcontextprotocol/node';
import type { AuthInfo } from '@modelcontextprotocol/server';
import { ApmClient, ApmError } from './apm.js';
import { createProtocolHandler } from './server.js';

/** 所有地址来自可信环境配置，客户端无法覆盖后端 origin。 */
const backendOrigin = process.env.APM_BACKEND_ORIGIN ?? 'http://127.0.0.1:8080';
const client = new ApmClient(backendOrigin);
const handler = createProtocolHandler(client);
const nodeHandler = toNodeHandler(handler, { maxRequestBodySize: 64 * 1024 });
const hosts = (process.env.MCP_ALLOWED_HOSTS ?? '127.0.0.1,localhost').split(',').map((value) => value.trim());
const origins = (process.env.MCP_ALLOWED_ORIGINS ?? '127.0.0.1,localhost').split(',').map((value) => value.trim());
const validateHost = hostHeaderValidation(hosts);
const validateOrigin = originValidation(origins);
const port = Number(process.env.PORT ?? '3010');
const bind = process.env.MCP_BIND ?? '127.0.0.1';

/** 预检返回固定错误字段，不把 Token 或后端诊断文本写入响应和日志。 */
function deny(response: import('node:http').ServerResponse, status: number, code: string, retryAfter?: string): void {
  response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store',
    ...(retryAfter ? { 'Retry-After': retryAfter } : {}) });
  response.end(JSON.stringify({ code }));
}

/** MCP 每次 HTTP 请求都预检 Token，工具调用本身还会二次验证。 */
const server = createServer(async (request, response) => {
  if (!validateHost(request, response) || !validateOrigin(request, response)) return;
  if (request.url === '/healthz') {
    try {
      const check = await fetch(new URL('/actuator/health', backendOrigin), {
        method: 'GET', redirect: 'error', signal: AbortSignal.timeout(2_000)
      });
      response.writeHead(check.ok ? 200 : 503, { 'Cache-Control': 'no-store' }).end();
    } catch {
      response.writeHead(503, { 'Cache-Control': 'no-store' }).end();
    }
    return;
  }
  if (request.url !== '/mcp') { response.writeHead(404).end(); return; }
  if (process.env.MCP_ENABLED !== 'true') { deny(response, 503, 'MCP_DISABLED'); return; }
  const headers = request.headers.authorization;
  if (typeof headers !== 'string' || !/^Bearer [^\s]+$/.test(headers) || request.headers['x-app-key']) {
    deny(response, 401, 'QUERY_TOKEN_INVALID'); return;
  }
  const token = headers.slice(7);
  try {
    const application = await client.get('/application', token);
    const appId = application !== null && typeof application === 'object' && 'appId' in application
      ? String(application.appId) : '';
    if (!appId) { deny(response, 503, 'QUERY_BACKEND_INVALID_RESPONSE'); return; }
    (request as typeof request & { auth: AuthInfo }).auth =
      { token, clientId: appId, scopes: ['apm:read'] };
    response.setHeader('Cache-Control', 'no-store');
    await nodeHandler(request, response);
  } catch (error) {
    const failure = error instanceof ApmError ? error : new ApmError('QUERY_BACKEND_UNAVAILABLE', 503, true);
    deny(response, failure.status, failure.code, failure.retryAfter);
  }
});

server.listen(port, bind);
process.on('SIGTERM', () => {
  server.close();
  void handler.close();
});
