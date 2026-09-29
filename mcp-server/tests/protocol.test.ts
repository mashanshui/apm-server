import assert from 'node:assert/strict';
import test from 'node:test';
import { Client, StreamableHTTPClientTransport } from '@modelcontextprotocol/client';
import { createProtocolHandler } from '../src/server.js';
import { ApmClient } from '../src/apm.js';

/** 官方客户端经真实 HTTP 协议调用同一服务工厂。 */
test('discovers and calls a read-only tool over Streamable HTTP', async () => {
  const handler = createProtocolHandler(new ApmClient('http://backend.test', async () =>
    new Response(JSON.stringify({ id: 'app-fixture' }), { status: 200 })));
  const transport = new StreamableHTTPClientTransport(new URL('http://test.local/mcp'), {
    fetch: (url, init) => handler.fetch(new Request(url, init), {
      authInfo: { token: 'test-placeholder', clientId: 'app-fixture', scopes: ['apm:read'] }
    })
  });
  const client = new Client({ name: 'apm-test-client', version: '0.1.0' }, {
    versionNegotiation: { mode: 'auto' }
  });
  try {
    await client.connect(transport);
    assert.equal(transport.protocolVersion, '2026-07-28');
    const discovery = await client.listTools();
    assert.equal(discovery.tools.length, 19);
    const result = await client.callTool({ name: 'get_application', arguments: {} });
    assert.equal((result.structuredContent as { data: { id: string } }).data.id, 'app-fixture');
  } finally {
    await client.close();
    await handler.close();
  }
});
