# APM 查询 MCP 工具 API

独立 TypeScript 服务在 /mcp 提供 Streamable HTTP，客户端需支持手工设置 Authorization: Bearer <应用查询Token>。每个 MCP HTTP 请求先调用固定 Java 后端的 /api/agent/v1/application 验证身份，工具查询再由后端重新验证。服务不接收客户端指定的后端 URL，不持有数据库账号，也不执行写入、SQL、HPROF 解析或任意网络访问。

| 工具 | 对应 HTTP 路由 |
|---|---|
| get_application | /application |
| get_crash_overview、get_crash_trend、list_crash_issues | /crashes/overview、/crashes/trend、/crashes/issues |
| list_crash_events、get_crash_event | /crashes/issues/{fingerprint}/events、/crashes/events/{eventId} |
| get_jank_overview、get_jank_trend、list_jank_issues | /janks/overview、/janks/trend、/janks/issues |
| list_jank_events、get_jank_event | /janks/issues/{fingerprint}/events、/janks/events/{eventId} |
| get_fps、get_suspension_rate | /jank-metrics/fps、/jank-metrics/suspension-rate |
| get_jank_metric_trend、get_jank_dimensions | /jank-metrics/trend、/jank-metrics/dimensions |
| get_memory_summary、get_memory_trend | /memory-metrics/summary、/memory-metrics/trend |
| list_memory_leak_issues、get_memory_leak_trend | /memory-leaks/issues、/memory-leaks/trend |

共 19 个工具。每个输入 schema 拒绝未知字段，只开放对应 HTTP 路由已有筛选；from/to 默认最近 24 小时并转为绝对 UTC，最长 31 天。游标续页必须显式重用首次绝对时间窗。输出的 data 保留后端业务字段和 null/零值，query 保留有效范围与筛选，evidence 仅列出后端现有应用、事件、指纹、构建或版本引用；另有 retrievedAt、truncated 和可选 continuation。

Crash/卡顿详情默认省略大证据字段并列出 availableSections。使用 section、offset、chunkBytes 续读；content 是该字段 JSON 的 UTF-8 字节段再作 Base64 编码，按 offset 拼接并解析可恢复原字段。续读传前页 contentDigest，若内容变化返回 EVIDENCE_CHANGED。单次后端响应上限 8 MiB，完整 MCP 结果（结构化副本和文本副本合计）上限 256 KiB；超限明确返回 QUERY_BACKEND_RESPONSE_TOO_LARGE 或 RESULT_TOO_LARGE，不静默删条目。分页不提供数据库快照，迟到数据可能改变后续页。

工具业务错误返回 isError，文本 JSON 包含 code、retryable、requestId、retryAfter；不转发不可信的后端错误消息，也不自动重试。MCP 入口缺失或无效 Bearer 为 HTTP 401，后端不可用为 503，关闭开关为 503 MCP_DISABLED。当前允许 HTTP 调试接入，MCP 不检查 HTTPS 代理标记；两个查询开关默认关闭，必须显式开启。Bearer 经 HTTP 明文传输，需限定受控环境；受信 HTTPS 是后续生产化要求。云端不得将 MCP 容器端口直接暴露给客户端。请求体最大 64 KiB。具体业务错误及限额见 [Agent HTTP API](agent-query-api.md)。
