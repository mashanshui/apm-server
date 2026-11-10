# APM 查询 MCP 工具 API

内存指标查询返回运行期观测峰值分布和runCount，按最后持久化采样时间归属，scene/foreground分别匹配各指标峰值上下文；下次启动补报后才可见，不兼容sampleCount别名。客户端联合验收仍待完成。内存工具要求合法非负整数runCount，拒绝sampleCount旧结果及缺失/非法计数；scene最长128、processName/版本/系统/机型最长256字符，趋势metric只支持pss/vss/java_heap。实际HTTP一致性与错误验证见[独立验收](../validation/memory-runtime-peaks/README.md)。

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


## 卡顿续页与查询失败

首次 `list_jank_issues` 或 `list_jank_events` 可以省略时间。收到结果后必须从 `query.from` 和 `query.to` 复制完整绝对时间（包括小数秒），与 `data.nextCursor` 一起传入下一次同一工具，并保持全部筛选。MCP 保留续页必须显式时间的约定；后端 HTTP 自行恢复时间的能力不改变此要求。

```json
{"from":"2026-10-01T00:00:00.123Z","to":"2026-10-02T00:00:00.456Z","cursor":"<首次返回的完整不透明 nextCursor>","limit":50}
```

事件工具另保留首次 fingerprint。不解析或拼装游标，页大小只改变条数。`INVALID_CURSOR`（400）须从第一页重新查询；`QUERY_TIMEOUT`（408）和 `QUERY_RESOURCE_LIMIT`（422）须缩小范围或增加筛选；`EVENT_STORE_UNAVAILABLE`（503）按暂时故障处理。协议测试覆盖上述传播、原样游标和小数秒时间，测试结果见[后端验收](../../backend/docs/knowledge-base/backend-api-query-validation.md)。

## 当前 Crash 堆栈契约

Crash 详情返回独立主 `stack:{format,content}` 与 `diagnostics`：JVM pending/failed 为 raw_chain、symbolicated 为 retrace_text；Native 为 raw_native 和 `native_symbolication_deferred`。Native 地址以字符串保留，其他 Native 线程按系统 TID、Java 快照按 Java ID 区分；未采集诊断为 null。详细字段见 [Crash API](crash-api.md#查询接口)。JVM 首次成功固定，mapping 删除重传不改写旧结果；Native 暂无 SO 符号还原，也不能创建分析任务。Issue 显示 temporary 与 buildId 或 resolved；旧临时指纹可能变为空列表，分页不是快照。

MCP 的 Crash 大片段为 `stack` 与 `diagnostics`，摘要不夹带两者正文；不为空时在 `availableSections` 中列出。续读摘要绑定对应片段的 JSON 字节；内容变化返回 EVIDENCE_CHANGED，须重新开始。未知状态或堆栈格式返回 QUERY_BACKEND_INVALID_RESPONSE。网页和 Agent 查询可见授权诊断；分析任务版本 3 的冻结证据仍只含主异常片段，版本 4 报告未扩展。19 个工具和应用授权不变。
