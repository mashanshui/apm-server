# 单次 JVM 崩溃本地只读分析提案

> 历史归档（2026-10-04）：本变更已完成 46/46 项，其执行方案已由 [add-current-code-crash-fix](../2026-10-04-add-current-code-crash-fix/tasks.md) 替代。保留原提案、设计、实施和验收事实，本次跳过旧规范同步；当前行为以[正式规范](../../../specs/current-code-crash-fix/spec.md)为准。

## Why

当前平台可查询和还原 Crash，但开发者仍需关联源码并分析根因。本变更保持单事件、固定提交与可追溯报告的目标，由开发者当前宿主 Agent 直接完成分析，Python 负责材料准备、任务生命周期和结果核验，为后续修复与云端执行保留独立契约。

## What Changes

- 从事件详情按 eventId 创建分析任务，OWNER、ADMIN、DEVELOPER 可创建，VIEWER 仅查看；保留构建核验、不可变证据和日常详情实时还原语义。
- 本地流程改为网页创建任务 → Skill 接收已有 taskId → Python 准备冻结证据及固定提交 → 宿主 Agent 读取与搜索源码、分析异常链 → Python 核验并回传 → 网页展示。
- 使用宿主已配置模型，本地链路移除独立 OpenCode 执行器和 DeepSeek 密钥要求；网页不增加模型管理。
- Python 保留独立凭据、稳定领取、租约、私有恢复、有界只读读取及搜索、源码引用核验、幂等回传和显式重试。
- **BREAKING** 调整固定 DeepSeek/OpenCode 的任务及结果协议，记录宿主来源和信息可信度；模型、版本、用量无法核验时标记未知。旧历史保留实际执行事实，不改写成宿主执行。
- **BREAKING** 分离本地材料工具关闭与宿主停止事实。Python 强制控制工具访问、输出大小和提交期限；无法强制控制宿主模型请求、Token 或中止时明确能力边界。未知停止继续阻止新尝试，模型自报停止不是停止证明。
- 本任务源码工具始终只读；宿主其他工具权限由本机授权管理，Skill 不构成操作系统隔离。凭据不返回模型或对话。
- 首个宿主验收沿用 Codex，其他工具按实际验证声明兼容；新执行流程重新验收，既有质量结果不作为宿主质量通过证据。
- 不执行源码修复、构建、审批、推送或 MR；不增加跨事件批量分析、云端调度、模型管理、MCP 迁移或通用执行器框架。

- Skill 随附 Python 运行时、锁定依赖与统一启动脚本，导出不含本地配置、虚拟环境及验收数据的 ZIP；用户无需另行检出 Worker 工程。

## Capabilities

### New Capabilities

- `crash-event-analysis`：构建登记、单事件证据、任务、宿主执行来源、结构化结果与历史。
- `local-analysis-worker`：独立身份、Python 任务工具、宿主 Skill 分析、引用核验、恢复与明确停止边界。

### Modified Capabilities

- `jvm-crash-monitoring`：分析专用不可变还原快照，日常实时还原保持原语义。
- `crash-frontend`：创建任务、自然语言指令、宿主执行信息、未知状态与分页报告。

## Impact

- 后端 agent 域和后续 Flyway 迁移调整执行配置及停止事实；不修改已发布迁移，不增加 ClickHouse 全量扫描。
- Python agent-worker 改为材料与任务控制工具；Skill 直接指导宿主推理。现有 TypeScript MCP 及 apm:read 权限保持不变。
- 前端调整任务执行指引、来源、模型未知与停止状态；管理员构建登记和凭据权限不变。
- 保留旧 34 项完成记录及验收历史，追加替换方案待实施任务，不把计划写成当前能力。
- Performance 仍为只读试点，既有独立测试页和 FPS 修复授权保持原范围，不新增 Android 修改。
- 实施同步根、后端、前端知识库、Worker/Skill、API 及验收；客户端事件协议不变。
