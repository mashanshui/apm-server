# 服务端 API 文档

本目录集中保存已经发布的服务端 HTTP 接口契约、请求与响应字段、统计口径和错误语义。客户端如何采集、持久化、重试和上传数据，统一从[客户端接入文档](../client-integration/README.md)进入。

跨入口身份约定：JSON 批量事件、卡顿 manifest 和内存报告 metadata 的 `processId` 均为必填 UUID v4；服务端不从 Android 数值 PID、进程名称或上传连接补值。内存报告原有可选 `sessionId` 边界保持不变。

## 文档目录

| 领域 | 文档 | 主要内容 |
|---|---|---|
| 通用错误 | [框架请求错误](request-errors.md) | MVC 正文、参数、校验、405/415 与安全边界 |
| 登录与应用 | [登录与应用管理 API](app-api.md) | Session、应用创建、不可变包名、永久 Key 与按权限查询 |
| 应用查询凭据 | [应用查询 Token 管理 API](query-token-api.md) | 只读应用 Token 创建、分页列表、撤销、期限及一次展示 |
| Agent 查询 | [Agent 只读查询 HTTP API](agent-query-api.md) | Token 复用、19 个 GET 入口、配额和错误 |
| 本地分析 | [单事件分析 API](analysis-api.md) | 单事件当前代码任务、应用 Worker、快照、有限修复结果与停止审计 |
| MCP | [APM 查询 MCP 工具 API](mcp-api.md) | 19 个只读工具、结构化证据、片段续读和结果边界 |
| Crash | [Crash API 与统计公式](crash-api.md) | JVM/Native 批量上报、总览、趋势、Issue、事件详情和统计状态 |
| Crash | [Crash 上报错误码](crash-error-codes.md) | 批次级/事件级错误、部分接受和重试语义 |
| 卡顿监控 | [卡顿监控服务端协议与查询 API](jank-server-api.md) | 卡顿 ZIP 落库、Issue、FPS、挂起率、趋势和多维查询 |
| 卡顿接收 | [卡顿压缩包解析与落库 API](stack-artifact-api.md) | `.rheajank.zip`、应用 Key、服务端派生质量、幂等和错误语义 |
| 内存指标 | [内存指标上传与查询 API](memory-metrics-api.md) | PSS、VSS、Java 堆采样上报、概览、趋势、筛选、统计和错误语义 |
| 内存异常 | [内存泄漏报告 API](memory-leak-reports-api.md) | multipart 的 metadata/report 文件、可选 HPROF 保存、问题聚合和趋势查询；不解析 HPROF |
| Android 符号表 | [Android 符号表管理 API](symbol-api.md) | 版本默认/专用 mapping、SO ZIP 原子批次、数据库分页、条件替换/删除及版本本轮进度 |

## 文档边界

- 本目录记录服务端正式契约；代码、控制器和 JSON Schema 是当前实现事实来源。
- Android SDK 对接步骤放在 `docs/client-integration/`，不在 API 文档中重复维护客户端队列与采集实现。
- 固定样本见[数据集目录](../datasets/README.md)，数据库初始化与部署见[运维资料](../operations/README.md)，独立跨端证据见[验收资料](../validation/README.md)；后端性能基线由[后端知识库](../../backend/docs/knowledge-base/README.md)维护，通过对应 API 文档建立链接。
- API、错误码或统计口径变化时，应同时更新对应控制器测试、专项文档和[平台知识库](../knowledge-base/README.md)。
