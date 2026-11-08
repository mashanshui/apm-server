# 运行期内存峰值上报：服务端变更提案

## Why

现有 `memory_sample` 表示单次采样，无法表达用户要求的“一次主进程运行分别累计 PSS、VSS、Java 堆最大值，下次启动再上传”。需要同步调整接收、存储、统计与展示，避免将运行峰值和历史普通样本混算。

## What Changes

- **BREAKING**：内存指标改为 `memory_peak_summary` / `memoryPeakSummary`，继续使用 v2 批量入口与 App Key；停止接收旧 `memory_sample`，旧数据保留但不进入新统计，不提供兼容转换。
- 接收原运行身份、版本、采集起点、最后已持久化采样时间，以及三项独立峰值的字节数、时间、前后台与 Activity 场景。
- 以最后已持久化采样时间归属运行记录，统计每次运行峰值的分布；概览和趋势数量改为 `runCount`，筛选上下文分别作用于每项峰值。
- 使用独立峰值表，沿用同步存储、事件去重、查询预算、Session/应用授权和只读 Agent 通道。
- 为峰值事件单独提供有界历史补报窗口，规划默认 90 天，与客户端事件过期口径对齐；其他事件保留现有窗口。
- Dashboard 标明“运行期观测峰值”和“有效运行数”；同步 MCP/Agent 结果说明、协议文档与共同验收样例。
- 本变更包含服务端仓库内的后端、Dashboard 和 MCP；不修改独立的内存泄漏报告/HPROF 链路。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `memory-metrics-server`：峰值汇总接收、历史窗口、持久化、去重与运行峰值统计。
- `memory-metrics-frontend`：运行数、独立峰值上下文筛选和峰值分布说明。
- `event-process-identity`：内存入口从普通样本切换为运行峰值，补传继续保存原运行身份。
- `apm-mcp-query`：内存只读查询同步运行峰值语义和 `runCount`。

## Impact

- 后端：`ingest` 信封/严格 Schema/分发，`memory` DTO、校验、双存储适配器、SQL 和测试，新增 ClickHouse 版本脚本；不改写已发布迁移。
- 前端：内存 API/类型、筛选、卡片、趋势与测试；MCP 内存工具说明、结果校验及测试。
- 文档：平台知识库、后端知识库、前端知识库、`docs/api/memory-metrics-api.md`、`docs/client-integration/memory-metrics.md` 及固定数据集。
- 来源：[梳理内存信息上报逻辑](codex://threads/01a1b4cb-05cf-7280-b105-3285b76c59b7)。上述为目标设计，当前代码仍是普通采样，历史测试不构成本变更验收。
- 客户端配套：[Performance 同名变更](../../../../../../AndroidStudioProjects/Performance/openspec/changes/archive/2026-11-08-report-memory-runtime-peaks/proposal.md)（外部检出依赖）。共同协议与联合验收唯一正文放在本变更 [design.md](design.md)，客户端引用；各自只执行本仓库任务。
- 顺序：先完成本仓库独立实现与自动化，再完成客户端独立实现与自动化，最后联合测试；联合测试前两边均不归档，详见 [tasks.md](tasks.md)。
