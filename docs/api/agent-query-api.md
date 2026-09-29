# Agent 只读查询 HTTP API

当前实现提供独立的 /api/agent/v1 GET 入口。调用方使用 Authorization: Bearer <应用查询Token>；appId 由 Token 推导，请求路径和查询参数都不接收调用方指定的应用 ID。Android 上报 X-App-Key、网页 Session 和 Cookie 不能替代 Token；同时携带 X-App-Key 会被拒绝。同一个 Token 可供 MCP 和其他受信查询客户端复用。

| 分类 | GET 路径 | 用途 |
|---|---|---|
| 应用 | /application | 当前应用的 appId、name、packageName |
| Crash | /crashes/overview、/crashes/trend、/crashes/issues | 概览、趋势、问题页 |
| Crash | /crashes/issues/{fingerprint}/events、/crashes/events/{eventId} | 问题内事件页、详情 |
| 卡顿 | /janks/overview、/janks/trend、/janks/issues | 概览、趋势、问题页 |
| 卡顿 | /janks/issues/{fingerprint}/events、/janks/events/{eventId} | 问题内事件页、详情 |
| 卡顿指标 | /jank-metrics/fps、/jank-metrics/suspension-rate | FPS、设备日挂起率 |
| 卡顿指标 | /jank-metrics/trend、/jank-metrics/dimensions | 指标趋势、白名单维度 |
| 内存指标 | /memory-metrics/summary、/memory-metrics/trend | PSS/VSS/Java 堆 |
| SDK 内存异常 | /memory-leaks/issues、/memory-leaks/trend | 问题页、趋势 |

共 19 个入口。字段、状态、公式与单位继续沿用 [Crash](crash-api.md)、[卡顿](jank-server-api.md)、[内存指标](memory-metrics-api.md)及[内存异常](memory-leak-reports-api.md)的业务契约。所有查询拒绝未知或重复参数；详情不接受查询参数。列表默认 limit=20、最大 100，内存异常问题页沿用 page/pageSize。Crash 游标绑定首次绝对时间窗与筛选条件，失效返回 INVALID_CURSOR，客户端应重新从第一页开始。

请求示例：GET /api/agent/v1/crashes/overview?from=2026-09-27T00%3A00%3A00Z&to=2026-09-28T00%3A00%3A00Z，携带 Authorization: Bearer <应用查询Token>。

服务端每次请求都查询 PostgreSQL 验证 Token。管理员撤销提交后，新请求立即拒绝；已认证的在途请求不会强行取消。响应设置 Cache-Control: no-store 和 X-Request-Id。此入口只允许 GET；管理操作仍需网页 Session、角色与 CSRF。

## 保护与错误

单实例默认每 Token 每分钟 60 次、同时 2 次，每应用每分钟 300 次、同时 8 次；无效凭据按来源 IP 每分钟 30 次。MCP 工具调用包含预检和实际查询，通常计入两次。超限返回 429 AGENT_RATE_LIMITED 与 Retry-After。单实例配额不跨副本共享。

| HTTP | 代码 | 处理 |
|---|---|---|
| 400 | INVALID_FILTER、INVALID_LIMIT 等 | 修正白名单参数 |
| 401 | QUERY_TOKEN_INVALID | 更换或重新创建 Token |
| 404 | EVENT_NOT_FOUND 等 | 当前应用内资源不存在 |
| 408 | QUERY_TIMEOUT | 缩小时间范围后重试 |
| 422 | QUERY_RESOURCE_LIMIT | 缩小查询范围 |
| 429 | AGENT_RATE_LIMITED | 遵守 Retry-After |
| 503 | QUERY_AUTH_UNAVAILABLE、EVENT_STORE_UNAVAILABLE、AGENT_QUERY_DISABLED | 检查后端或开关状态 |

APM_AGENT_QUERY_ENABLED=false 是默认状态；启用后才可查询和创建新 Token。关闭时仍可通过网页列出并撤销现有 Token。上报与网页 Session 查询不受此开关影响。MCP 协议映射见 [MCP 工具 API](mcp-api.md)。
