import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { setTimeout as delay } from 'node:timers/promises';
import { Client, StreamableHTTPClientTransport } from '@modelcontextprotocol/client';
import { toNodeHandler } from '@modelcontextprotocol/node';
import { ApmClient } from '../dist/src/apm.js';
import { createProtocolHandler } from '../dist/src/server.js';

/** 密集验收遵守服务端限流窗口；只重试明确的限流，不掩盖其他失败。 */
async function withinRateWindow(operation) {
  // 每个查询最多尝试三次，避免故障时无限等待。
  for (let attempt = 0; attempt < 3; attempt++) {
    /** HTTP 异常或 MCP 错误中提供的可重试限流信息。 */
    let limited;
    try {
      /** 官方工具实际结果；成功响应保持原样供调用方断言。 */
      const result = await operation();
      if (!result?.isError) return result;
      limited = JSON.parse(result.content.find(item => item.type === 'text').text);
      if (limited.code !== 'AGENT_RATE_LIMITED') return result;
    } catch (error) {
      if (error.code !== 'AGENT_RATE_LIMITED') throw error;
      limited = error;
    }
    /** 有界等待真实 Retry-After，不能修改生产限流配置或无限重试。 */
    const seconds = Number(limited.retryAfter);
    assert.ok(Number.isFinite(seconds) && seconds >= 0 && seconds <= 60);
    await delay((seconds + 1) * 1000);
  }
  throw new Error('验收查询超过三次限流窗口');
}

/** 从标准输入读取临时测试配置，Token 不写入命令行或输出。 */
async function configuration() {
  /** 有界测试配置字节。 */
  const chunks = [];
  for await (const chunk of process.stdin) chunks.push(chunk);
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

/** 通过真实 MCP HTTP 与真实 Java Agent HTTP 比较同一固定集。 */
async function verify() {
  /** 本次临时配置和固定后端。 */
  const config = await configuration();
  const backend = new ApmClient(config.origin);
  const handler = createProtocolHandler(backend);
  const nodeHandler = toNodeHandler(handler);
  /** 本测试的临时 HTTP 入口，认证身份仍由真实后端查询再次校验。 */
  const server = createServer((request, response) => {
    request.auth = { token: config.token, clientId: config.appId, scopes: ['apm:read'] };
    void nodeHandler(request, response);
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  /** 使用官方客户端而非手写 JSON-RPC 请求。 */
  const client = new Client({ name: 'memory-peak-validation', version: '1.0.0' });
  try {
    await client.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${server.address().port}/mcp`)));
    const tools = await client.listTools();
    assert.match(tools.tools.find(tool => tool.name === 'get_memory_summary').description, /runCount/);
    /** 场景筛选逐项验证，不能把同轮另一项的上下文混入。 */
    for (const filters of config.filters ?? [{}, { scene: 'A', foreground: true }, { foreground: false }]) {
      /** 实际 SDK 联调可核对全部指标，独立固定集仍保留原默认。 */
      const metrics = config.metrics ?? ['pss'];
      for (const [name, path, extra] of [
        ['get_memory_summary', '/memory-metrics/summary', {}],
        ...metrics.map(metric => ['get_memory_trend', '/memory-metrics/trend', { metric, interval: 'hour' }]),
      ]) {
        const args = { from: config.from, to: config.to, ...filters, ...extra };
        const expected = await withinRateWindow(() => backend.get(path, config.token, Object.fromEntries(Object.entries(args).map(([key,value]) => [key, String(value)]))));
        const result = await withinRateWindow(() => client.callTool({ name, arguments: args }));
        assert.notEqual(result.isError, true, `${name}: ${JSON.stringify(result.isError ? result.content : [])}`);
        assert.deepEqual(result.structuredContent.data, expected);
        assert.doesNotMatch(JSON.stringify(result.structuredContent.data), /sampleCount/);
      }
    }
    /** 撤销使用无效凭据验证错误不会被包装成空数据。 */
    config.token = 'invalid-test-token';
    const denied = await client.callTool({ name: 'get_memory_summary', arguments: { from: config.from, to: config.to } });
    assert.equal(denied.isError, true);
    assert.match(JSON.stringify(denied.content), /QUERY_TOKEN_INVALID/);
    process.stdout.write('真实 MCP/Agent 峰值概览、趋势、筛选与认证错误一致\n');
  } finally {
    await client.close();
    await handler.close();
    await new Promise(resolve => server.close(resolve));
  }
}

await verify();
