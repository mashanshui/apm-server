import assert from 'node:assert/strict';
import { createServer, request as httpRequest } from 'node:http';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import test from 'node:test';
import { Client, StreamableHTTPClientTransport } from '@modelcontextprotocol/client';

/** 在真实 Node HTTP 入口与假 Java 后端间验证每次请求都重新预检。 */
test('runtime rejects revoked tokens on an existing client and isolates applications', async () => {
  const revoked = new Set<string>();
  const backend = createServer((request, response) => {
    if (request.url === '/actuator/health') {
      response.writeHead(200).end('{"status":"UP"}');
      return;
    }
    const bearer = request.headers.authorization;
    if (!bearer || revoked.has(bearer) || !['Bearer a', 'Bearer b'].includes(bearer)) {
      response.writeHead(401, { 'Content-Type': 'application/json' })
        .end('{"code":"QUERY_TOKEN_INVALID"}');
      return;
    }
    const appId = bearer === 'Bearer a' ? 'app-a' : 'app-b';
    response.writeHead(200, { 'Content-Type': 'application/json' })
      .end(JSON.stringify({ appId, status: 'no_data' }));
  });
  backend.listen(0, '127.0.0.1');
  await once(backend, 'listening');
  const backendAddress = backend.address();
  assert(backendAddress && typeof backendAddress !== 'string');
  const port = await freePort();
  const child = spawn(process.execPath, ['dist/src/index.js'], {
    env: { ...process.env, PORT: String(port), MCP_BIND: '127.0.0.1', MCP_ENABLED: 'true',
      APM_BACKEND_ORIGIN: `http://127.0.0.1:${backendAddress.port}` },
    stdio: 'ignore'
  });
  const clients: Client[] = [];
  try {
    await ready(port);
    for (const token of ['a', 'b']) {
      const transport = new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${port}/mcp`), {
        requestInit: { headers: { Authorization: `Bearer ${token}` } }
      });
      const client = new Client({ name: 'runtime-test', version: '0.1.0' });
      clients.push(client);
      await client.connect(transport);
      const result = await client.callTool({ name: 'get_crash_overview', arguments: {} });
      assert.equal((result.structuredContent as { data: { appId: string } }).data.appId,
        token === 'a' ? 'app-a' : 'app-b');
    }
    revoked.add('Bearer a');
    await assert.rejects(clients[0]!.callTool({ name: 'get_application', arguments: {} }));
    const stillValid = await clients[1]!.callTool({ name: 'get_application', arguments: {} });
    assert.equal((stillValid.structuredContent as { data: { appId: string } }).data.appId, 'app-b');
    const badHost = await new Promise<number>((resolve, reject) => {
      const probe = httpRequest({ hostname: '127.0.0.1', port, path: '/mcp', method: 'POST',
        headers: { Host: 'evil.example', Authorization: 'Bearer b', 'Content-Type': 'application/json' } },
      (reply) => { reply.resume(); resolve(reply.statusCode ?? 0); });
      probe.on('error', reject);
      probe.end('{}');
    });
    assert.equal(badHost, 403);
    const badOrigin = await fetch(`http://127.0.0.1:${port}/mcp`, {
      method: 'POST', headers: { Origin: 'https://evil.example', Authorization: 'Bearer b' }, body: '{}'
    });
    assert.equal(badOrigin.status, 403);
  } finally {
    for (const client of clients) await client.close().catch(() => undefined);
    child.kill('SIGTERM');
    await once(child, 'exit');
    backend.close();
    await once(backend, 'close');
  }
});


/** 使用内核分配空闲端口，避免与本机正在运行的服务冲突。 */
async function freePort(): Promise<number> {
  const server = createServer();
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const address = server.address();
  assert(address && typeof address !== 'string');
  server.close();
  await once(server, 'close');
  return address.port;
}

/** 仅在 MCP 进程健康且后端可用时继续测试。 */
async function ready(port: number): Promise<void> {
  for (let attempt = 0; attempt < 50; attempt++) {
    try {
      const result = await fetch(`http://127.0.0.1:${port}/healthz`);
      if (result.ok) return;
    } catch {
      // 启动竞态由有限重试吸收。
    }
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
  throw new Error('MCP runtime did not become healthy');
}
