# 应用查询 Token 与 TypeScript MCP 实施提案

## Why

现有 APM 已有 Crash、卡顿和内存查询，但仅支持网页 Session，外部 Agent 缺少可复用、按应用隔离的查询凭据和 MCP 工具。Crash 聚合与列表还会读取应用全部原始记录及堆栈后在 JVM 筛选，Agent 连续查询会放大资源成本，需要在开放查询时一起治理。

## What Changes

- 应用设置增加查询 Token 创建、列表、撤销；仅 OWNER/ADMIN 管理，绑定单个应用，固定只读权限，支持多个 Token，完整值仅创建时展示。有效期仅 30/90/365 天，默认 90 天，不允许永久有效。
- Java identity 模块统一管理 Token 和查询身份；新增 `/api/agent/v1` 只读入口供 MCP 和其他 Agent 复用，网页 Session 与 Android 上报 App Key 保持原边界。
- 根目录新增独立 TypeScript `mcp-server/`，首版使用 HTTP MCP，覆盖现有 Crash、Jank、卡顿指标、内存指标及内存异常报告查询；通过固定后端 HTTP 地址访问，不直连数据库。
- 工具采用白名单、结构化输出、请求级身份隔离、有限响应和证据引用；保留原有单位、分母、空值和采样质量语义。
- Crash 筛选、精确聚合、排序与分页下推 ClickHouse；统计和列表不加载堆栈，增加数据库资源限制及请求预算传递。
- **BREAKING**：Crash 列表游标改为绑定查询条件的版本化游标；旧、损坏或不匹配的游标明确拒绝，不再回退第一页。HTTP 路由和统计字段保持不变。
- 增加鉴权、契约、并发隔离、真实数据库及目标 MCP 客户端验收，同步部署与中文文档。

## Capabilities

### New Capabilities

- `application-query-tokens`：应用查询 Token 生命周期、管理界面、统一只读 Agent HTTP 查询与权限隔离。
- `apm-mcp-query`：TypeScript MCP 查询工具、结果边界、固定后端访问、传输部署与客户端验收。

### Modified Capabilities

- `jvm-crash-monitoring`：补齐查询执行保护、受控分页与数据库侧统计要求，保留原有精确统计及实时符号化语义。

## Impact

- 后端：identity Token 表和新增 Flyway 迁移、独立安全过滤链、业务域公开查询接口、Crash 查询端口与 ClickHouse SQL、受预算控制的查询客户端；不改变上报事件或已发布迁移。
- 前端：应用设置页 Token 区域、API 类型、请求状态和敏感值清理；Crash 分页错误处理。
- 新工程：`mcp-server/` 的运行、类型检查、测试、构建与容器文件；MCP SDK、Node.js 版本在互通验证后精确锁定。
- 运维：Compose、反向代理、当前 HTTP 调试接入、查询限额、健康检查和关闭 Agent 入口的回滚开关；受信 HTTPS 接入列为后续生产化任务。首版单实例，无新增数据库或缓存。
- 文档：根、后端、前端知识库及 API 目录同步；Android 客户端上传协议不变，仅检查交叉引用，无需修改 SDK。旧 Agent 平台方案中“共享 MCP 后置”由本次确认调整为“独立只读查询先行”，实施时显式同步，不把调度/修复能力标为已实现。
- 非目标：自动修复、Worker/Task/Run、源码检出与构建映射、模型托管、审批、CI、HPROF 解析、通用 SQL/任意 URL 工具、OAuth 授权服务器和不可变报告存储。
