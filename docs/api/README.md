# 服务端 API 文档

本目录集中保存已经发布的服务端 HTTP 接口契约、请求与响应字段、统计口径和错误语义。客户端如何采集、持久化、重试和上传数据，统一从[客户端接入文档](../client-integration/README.md)进入。

## 文档目录

| 领域 | 文档 | 主要内容 |
|---|---|---|
| 登录与应用 | [登录与应用管理 API](app-api.md) | Session、应用创建、不可变包名、永久 Key 与按权限查询 |
| JVM Crash | [Crash API 与统计公式](crash-api.md) | 批量上报、总览、趋势、Issue、事件详情和统计状态 |
| JVM Crash | [Crash 上报错误码](crash-error-codes.md) | 批次级/事件级错误、部分接受和重试语义 |
| 卡顿监控 | [卡顿监控服务端协议与查询 API](jank-server-api.md) | 卡顿 ZIP 落库、Issue、FPS、挂起率、趋势和多维查询 |
| 卡顿接收 | [卡顿压缩包解析与落库 API](stack-artifact-api.md) | `.rheajank.zip`、应用 Key、服务端派生质量、幂等和错误语义 |
| 内存指标 | [内存指标上传与查询 API](memory-metrics-api.md) | PSS、VSS、Java 堆采样上报、概览、趋势、筛选、统计和错误语义 |
| 内存异常 | [内存泄漏报告 API](memory-leak-reports-api.md) | multipart 的 metadata/report 文件、可选 HPROF 保存、问题聚合和趋势查询；不解析 HPROF |

## 文档边界

- 本目录记录服务端正式契约；代码、控制器和 JSON Schema 是当前实现事实来源。
- Android SDK 对接步骤放在 `docs/client-integration/`，不在 API 文档中重复维护客户端队列与采集实现。
- 固定数据集、性能基线、数据库初始化和部署文档继续保留在 `docs/` 下，通过对应 API 文档建立链接。
- API、错误码或统计口径变化时，应同时更新对应控制器测试、专项文档和[平台知识库](../knowledge-base/README.md)。
