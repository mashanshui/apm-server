# Android APM 平台知识库

本知识库是项目的中文总入口，用于连接目标架构、当前实现、接口文档、验证证据和待确认事项。原始方案见《[Android APM 服务端与 Dashboard 技术方案](../../Android_APM服务端与Dashboard技术方案.md)》。

阅读时遵循以下事实优先级：当前代码、数据库迁移和已发布 API 优先于知识库描述；知识库优先于原始方案中的历史规划。发现冲突时应记录差异并确认，不能静默覆盖。

2026-09-30 后端知识库已完成源码、配置、迁移和测试核对，具体差异见[后端核对记录](../../backend/docs/knowledge-base/00-当前实现与验证边界.md#2026-09-30-后端文档核对与差异修正)。卡顿个例统计截断、报告隐私与查询预算、架构门禁缺口按当前实现记录；本次回归结果见[后端测试记录](../../backend/docs/knowledge-base/06-测试与质量保障.md#2026-09-30-文档核对与后端回归)。

## 从这里开始

| 你的问题 | 首选入口 |
|---|---|
| 现在已经实现了什么，验证到什么程度 | [当前实现与验证边界](00-当前实现与验证边界.md) |
| 平台解决什么问题，当前范围是什么 | [产品目标与范围](01-产品目标与范围.md) |
| 系统如何拆分，运行时链路是什么 | [总体架构与模块](02-总体架构与模块.md) |
| API、客户端或前端如何接入 | [交付与接入文档](#交付与接入文档) |
| 设备、启动和进程实例身份如何约定 | [事件模型与数据存储](03-事件模型与数据存储.md#进程实例身份边界) |
| 为什么采用当前技术方案 | [架构决策记录](10-架构决策记录.md) |
| 哪些参数或能力尚未确定 | [待确认事项](13-待确认事项.md) |

当前结论：仓库已形成 JVM Crash、卡顿、PSS/VSS/Java 堆指标和 SDK 内存异常报告的开发闭环，统一接收并保存 UUID v4 `processId`；Java 后端采用模块化单体，包含领域专属 ClickHouse 存储、Session/应用管理、网页 mapping 与请求内 Retrace、查询 Token 和只读 Agent HTTP；Vue 提供浅色 PC 控制台，TypeScript MCP 独立部署。processor `1.0.2` 已通过实际设备 ZIP 的本机解析及云端写入/重复/详情验收；Nginx HTTP 入口已落地，HTTPS 生产网关、生产容量、故障恢复和完整多租户运营仍未完成，详见[当前实现与验证边界](00-当前实现与验证边界.md)。

## 核心知识地图

Agent 扩展的目标设计见 [APM Agent 平台方案](../../apm-agent-platform-design.md)。应用查询 Token、独立只读 Agent HTTP 入口及 TypeScript MCP 已实现，对应 OpenSpec 变更已归档，默认开关关闭；本地双应用真实数据和云端五容器入口冒烟见 [Agent 查询链路记录](../agent-query-e2e-validation.md)。2026-09-29 调试环境已显式开启两个查询开关；云端有效 Token 查询、审批、隔离修复和确定性 CI 验证仍未完成。查询契约见 [Agent HTTP API](../api/agent-query-api.md) 和 [MCP 工具 API](../api/mcp-api.md)。

### 产品与架构

| 主题 | 主要回答 |
|---|---|
| [产品目标与范围](01-产品目标与范围.md) | 为什么建设、首期边界和最小闭环是什么 |
| [总体架构与模块](02-总体架构与模块.md) | 目标拓扑、当前代码边界和技术栈是什么 |
| [架构决策记录](10-架构决策记录.md) | 已接受、暂定和待决策的技术选择是什么 |

### 数据、接口与安全

| 主题 | 主要回答 |
|---|---|
| [事件模型与数据存储](03-事件模型与数据存储.md) | PostgreSQL、ClickHouse、对象存储如何分工，事件如何建模 |
| [上报协议与可靠性](04-上报协议与可靠性.md) | 批量、gzip、校验、错误、重试和去重语义是什么 |
| [查询与 Dashboard](05-查询与Dashboard.md) | Crash、卡顿和内存查询 API、Grafana 与 Vue 如何分工，统计口径是什么 |
| [安全与隐私](06-安全与隐私.md) | 登录、应用授权、上报 Key、脱敏和多租户边界是什么 |

### 交付与治理

| 主题 | 主要回答 |
|---|---|
| [部署与运维](07-部署与运维.md) | 本地与目标部署拓扑、健康、备份和扩容边界是什么 |
| [测试与质量保障](08-测试与质量保障.md) | 测试分层、最新验证命令和证据边界是什么 |
| [实施路线图](09-实施路线图.md) | 已完成阶段、后续顺序和扩展触发条件是什么 |
| [待确认事项](13-待确认事项.md) | 容量、功能、可靠性和工程决策还缺什么结论 |

### 快速参考

| 主题 | 主要回答 |
|---|---|
| [术语表](11-术语表.md) | 关键缩写、状态和统计概念是什么意思 |
| [常见问题](12-常见问题.md) | 常见选型和边界问题的简短答案是什么 |
| [参考资料](14-参考资料.md) | 原方案、官方资料和仓库专项资料在哪里 |

## 交付与接入文档

云服务器连接参数与历史验证边界见 [SSH 连接说明](../cloud-ssh.md)，操作授权遵循[仓库协作规则](../../AGENTS.md#云服务器访问)。

| 使用者 | 文档入口 |
|---|---|
| Android SDK 开发 | [客户端接入目录](../client-integration/README.md)、[内存指标上传接入](../client-integration/memory-metrics.md)、[内存泄漏报告上传接入](../client-integration/memory-leak-reports.md)、[卡顿监控上传接入](../client-integration/jank-monitoring.md)、[卡顿压缩包上传接入](../client-integration/stack-artifact-upload.md)、[卡顿压缩包 manifest v3](../client-integration/jank-artifact-manifest.md)、[JVM Crash 上传接入](../client-integration/crash-client-integration.md) |
| 后端接口联调 | [服务端 API 目录](../api/README.md)、[内存指标 API](../api/memory-metrics-api.md)、[内存泄漏报告 API](../api/memory-leak-reports-api.md)、[Crash API 与统计公式](../api/crash-api.md)、[Crash 错误码](../api/crash-error-codes.md)、[卡顿监控服务端 API](../api/jank-server-api.md)、[卡顿压缩包解析与落库 API](../api/stack-artifact-api.md)、[登录与应用管理 API](../api/app-api.md) |
| 数据与验收 | [Crash 固定数据集](../crash-fixed-dataset.md)、[内存固定数据集](../memory-fixed-dataset.md)、[内存泄漏固定数据](../memory-leak-fixed-dataset.md)、[卡顿固定数据集](../jank-fixed-dataset.md)、[卡顿指标性能基线](../../backend/docs/knowledge-base/jank-performance-baseline.md) |
| ClickHouse 与 Grafana | [ClickHouse 本地初始化](../clickhouse-local.md)、[Grafana/ClickHouse 安装与应用接入](../grafana-clickhouse-install.md)、[灰度、开关与回滚](../crash-rollout.md) |
| Web 前端开发 | [前端知识库](../../frontend/docs/knowledge-base/README.md) |
| Android 符号表 | [符号表管理 API](../api/symbol-api.md)、[网页上传与生效语义](../client-integration/symbol-mapping.md) |
| Agent 只读查询 | [查询 Token 管理 API](../api/query-token-api.md)、[Agent HTTP API](../api/agent-query-api.md)、[MCP API](../api/mcp-api.md)、[MCP 运行说明](../../mcp-server/README.md)、[本地与云端验收记录](../agent-query-e2e-validation.md) |

## 推荐阅读路径

- 新成员：本页 → [当前实现与验证边界](00-当前实现与验证边界.md) → [产品目标与范围](01-产品目标与范围.md) → [总体架构与模块](02-总体架构与模块.md) → [术语表](11-术语表.md)。
- Android SDK 开发：本页 → [客户端接入目录](../client-integration/README.md) → [事件模型与数据存储](03-事件模型与数据存储.md) → [上报协议与可靠性](04-上报协议与可靠性.md) → [安全与隐私](06-安全与隐私.md)。
- 后端开发：本页 → [当前实现与验证边界](00-当前实现与验证边界.md) → [总体架构与模块](02-总体架构与模块.md) → [上报协议与可靠性](04-上报协议与可靠性.md) → [查询与 Dashboard](05-查询与Dashboard.md) → [测试与质量保障](08-测试与质量保障.md)。
- 前端开发：本页 → [前端知识库](../../frontend/docs/knowledge-base/README.md) → [查询与 Dashboard](05-查询与Dashboard.md) → [安全与隐私](06-安全与隐私.md)。
- 数据与运维：本页 → [事件模型与数据存储](03-事件模型与数据存储.md) → [部署与运维](07-部署与运维.md) → [测试与质量保障](08-测试与质量保障.md)。
- 维护负责人：本页 → [当前实现与验证边界](00-当前实现与验证边界.md) → [实施路线图](09-实施路线图.md) → [架构决策记录](10-架构决策记录.md) → [待确认事项](13-待确认事项.md)。

## 内容归属与维护约定

1. 平台实现状态和验证层级在[当前实现与验证边界](00-当前实现与验证边界.md)汇总；平台端到端证据在[测试与质量保障](08-测试与质量保障.md)维护，后端和前端专项命令、数量与日期分别由各自知识库维护。
2. 原理、长期约束和领域模型写入对应主题页；已经接受的关键技术取舍写入[架构决策记录](10-架构决策记录.md)。
3. 未确定的容量、SLA、保留期限、多租户和运维参数写入[待确认事项](13-待确认事项.md)，不要混入“当前已实现”描述。
4. 正式 API、事件字段或统计口径变更时，同时更新主题页、专项 API 文档、示例和测试要求。
5. 前端实现细节统一维护在[前端知识库](../../frontend/docs/knowledge-base/README.md)；平台知识库只保留跨端架构、正式接口、安全和部署边界。
6. 新增文档必须使用中文，标题直接表达要回答的问题，并从本页或所属专项目录建立入口。
7. 只记录已验证的事实，并明确区分代码存在、自动化验证、外部环境冒烟和生产就绪。

## 工程与知识库边界

仓库根目录负责平台架构、跨端约定、共同数据集与统一开发入口。`backend/` 和 `frontend/` 是并列独立工程。

| 入口 | 唯一维护范围 |
|---|---|
| [后端知识库](../../backend/docs/knowledge-base/README.md) | Java 模块、内部链路、持久化、安全实现、构建配置、后端测试与性能证据 |
| [前端知识库](../../frontend/docs/knowledge-base/README.md) | 页面、路由、状态、前端开发与测试 |
| [API 文档](../api/README.md) | 对外字段、错误语义与统计口径 |
| [客户端接入](../client-integration/README.md) | Android 构造事件、队列、上传和重试 |

后端详细测试计数与工程命令只在后端维护，平台状态页保留能力摘要和证据入口。拆分章节的来源映射见[后端迁移索引](../../backend/docs/knowledge-base/README.md#本次内容迁移索引)。
