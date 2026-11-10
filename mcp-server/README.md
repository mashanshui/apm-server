# APM 查询 MCP 服务

内存指标查询返回运行期观测峰值分布和runCount，按最后持久化采样时间归属，scene/foreground分别匹配各指标峰值上下文；下次启动补报后才可见，不兼容sampleCount别名。客户端联合验收仍待完成；服务端实际HTTP与官方客户端证据见[独立验收](../docs/validation/memory-runtime-peaks/README.md)。内存工具拒绝旧sampleCount及非法runCount结果，scene最长128、processName及版本/系统/机型最长256字符，趋势metric仅接受pss/vss/java_heap。

独立 TypeScript 工程，将官方 MCP Streamable HTTP 工具映射到 Java 后端固定的 /api/agent/v1 只读接口。MCP 不连接数据库，也不接受客户端指定的上游 URL。完整工具与错误契约见 [MCP API](../docs/api/mcp-api.md)；Token 管理见 [管理 API](../docs/api/query-token-api.md)。

## 结构

| 路径 | 用途 |
|---|---|
| src/index.ts | HTTP 入口、逐请求预检、Host/Origin 校验及健康检查 |
| src/apm.ts | 固定后端客户端、响应流上限和错误分类 |
| src/server.ts | 19 个工具、白名单 schema、UTC 时间窗和证据片段 |
| tests/ | 官方 SDK 协议及工具契约测试 |
| Dockerfile | 独立无数据库凭据的运行镜像 |

## 锁定版本

| 组件 | 版本 |
|---|---|
| Node.js | 24.19.0；允许同一 24 系列且不低于该版本 |
| npm | 11.6.2 |
| TypeScript | 5.9.3 |
| MCP server/node/client SDK | 2.1.0 |
| MCP 协议 | 2026-07-28 |
| Inspector CLI | 2.8.0 |

## 本机启动

需要 Node.js 24 与 npm 11。按顺序执行 npm ci、npm run typecheck、npm test、npm run build，然后设置 APM_BACKEND_ORIGIN=http://127.0.0.1:8080、MCP_ENABLED=true、MCP_ALLOWED_HOSTS=127.0.0.1,localhost、MCP_ALLOWED_ORIGINS=127.0.0.1,localhost，再执行 npm start。默认监听 127.0.0.1:3010/mcp。后端也须设置 APM_AGENT_QUERY_ENABLED=true。完整 Token 只从网页创建结果复制到支持手工 Bearer 的受信 MCP 客户端，不写入代码、日志、命令历史或版本库。

Inspector CLI 2.8.0 可用 --transport http --protocol-era modern --header "Authorization: Bearer <应用查询Token>" 手工配置凭据，并以 --method tools/list、--method tools/call 检查发现和调用。2026-09-29 已在本地真实 PostgreSQL/ClickHouse 双应用数据上完成 Inspector 查询、错误、分片及断连验证；范围见[端到端验收记录](../docs/validation/agent-query-e2e-validation.md)。

## 部署与保护

| 变量 | 默认值 | 用途 |
|---|---|---|
| MCP_ENABLED | false | 独立关闭 MCP 入口 |
| APM_BACKEND_ORIGIN | http://127.0.0.1:8080 | 固定 Java 后端 origin，不得包含路径、账号或 URL 参数 |
| MCP_BIND / PORT | 127.0.0.1 / 3010 | 本机监听；Compose 容器内绑定 0.0.0.0 |
| MCP_ALLOWED_HOSTS | 127.0.0.1,localhost | 请求 Host 白名单，部署域名须显式加入 |
| MCP_ALLOWED_ORIGINS | 127.0.0.1,localhost | 带 Origin 请求的来源主机白名单 |

请求体最大 64 KiB，后端响应最大 8 MiB，最终工具结果最大 256 KiB。每个 HTTP 请求预检 Token，工具实际查询再次验证；不自动重试，禁用重定向。/healthz 检查固定后端的 /actuator/health，依赖不可用时返回 503。Compose 只让 MCP 接入内部 backend 网络，对外由 Nginx /mcp 代理。当前允许 HTTP 调试接入，不检查 HTTPS 代理标记；两个查询开关默认关闭，必须显式开启。HTTP 上传输的 Bearer 可被链路观察者读取，因此仅在受控环境使用；受信 HTTPS 网关仍需后续完成生产化验收。不得把 MCP 容器端口直接映射到公网。回滚时同时关闭 MCP_ENABLED 与 APM_AGENT_QUERY_ENABLED，保留 Token 列表和撤销。

## 当前 Crash 堆栈契约

Crash 详情包含独立主 `stack:{format,content}` 和 `diagnostics`，未采集诊断为 null；JVM 使用 raw_chain 或 retrace_text，Native 使用 raw_native 和 `native_symbolication_deferred`。Native 地址不转为 JavaScript 数字，Native 线程按系统 TID、Java 线程按 Java ID 区分。JVM 主异常首次成功固定，mapping 删除重传不改写旧结果；Native 不提供 SO 符号还原或分析任务。Issue 显示 temporary 与 buildId 或 resolved；旧临时指纹可能变为空列表，分页不是快照。

MCP 的 Crash 大片段名称为 `stack` 和 `diagnostics`，摘要不夹带两者正文。续读绑定对应 JSON 字节摘要，内容变化返回 EVIDENCE_CHANGED。未知状态或格式返回 QUERY_BACKEND_INVALID_RESPONSE。冻结分析证据版本 3 仍只包含主异常片段，报告版本 4 未扩展；授权查询现场与冻结证据不是同一份材料。
