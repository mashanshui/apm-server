# 后端接口正确性修复提案

## 变更原因

接口审查发现卡顿统计截断、内存泄漏报告全量读取、登录会话标识不轮换、框架错误响应不统一及 PATCH 遗漏字段被覆盖五项问题，2026-10-04 源码复核确认这些路径仍存在。需要在现有模块内修复统计、安全和请求契约，并用真实数据库及 HTTP 验证补齐目前小样本测试的缺口。

## 变更内容

- 将卡顿总览、趋势、Issue 聚合及事件分页下推 ClickHouse；页大小只限制返回条目，保持全范围精确统计、去重及耗时口径。
- **BREAKING**：卡顿列表改为版本化、绑定应用和筛选时间窗的不透明游标，旧纯指纹/事件 ID 游标返回 `400 INVALID_CURSOR`；网页提供重新查询入口。
- 将内存泄漏报告的引用链筛选、去重、聚合和分页下推数据库，保留现有页码、响应字段和全范围占比分母；复用查询预算，超限明确失败。
- 登录成功时轮换已有 Session ID，刷新 CSRF Token，并验证登录后首个写请求与旧会话失效行为。
- 为进入 MVC 的 JSON 解析、参数绑定、必填项和 Bean Validation 错误补齐 `ApiErrorResponse`，保留既有业务错误与安全过滤链语义。
- **BREAKING**：明确应用 `PATCH` 为部分更新：未提交字段保留；`description: null` 或空白字符串清空；显式空名称非法；空对象为无副作用读取。用户已选择此方案，调用方不得再依赖省略描述来清空数据。

## 能力范围

### 新增能力

- `backend-api-errors`：框架层请求错误使用稳定、安全、可供调用方处理的错误响应。

### 修改能力

- `jank-monitoring-server`：完整统计、数据库聚合、受限查询和可继续遍历的游标契约。
- `memory-leak-reports-server`：数据库侧完成筛选及分页、保持引用链统计口径并限制资源。
- `web-user-authentication`：认证成功时轮换会话标识和 CSRF Token。
- `project-management`：应用信息 PATCH 的字段存在性、清空及原子更新语义。
- `jank-monitoring-frontend`：不透明游标透传、失效恢复及查询失败显示。

## 影响范围

- 后端：`jank`、`memory` 查询服务与仓储，`identity` 登录和应用更新，`bootstrap/internal/web/ApiExceptionHandler`；复用 `platform/api/QueryBudget` 与 `ClickHouseHttpClient.executeQuery`，不引入中间件或新服务。
- 前端：卡顿分页交互、应用更新请求类型及会话/CSRF 联调；Agent HTTP 与 MCP 共用的查询结果随业务查询修正，加入契约回归。
- 数据：优先查询现有 `apm_jank_event` 与 `apm_memory_report` 去重事实；本方案不新增事实副本、物化视图或数据回填。
- 文档：实施时同步根知识库、后端及前端知识库，更新应用、卡顿、内存泄漏、Agent/MCP API 与固定数据验收说明。Android 上报字段与采集流程无变更，客户端文档只核对受全局错误处理影响的失败响应。
- 范围：不改变 FPS、挂起率、常规内存采样公式，不扩展用户管理、分析任务生命周期、附件下载或 HTTPS 应用逻辑；云端部署另行执行。
- 证据：2026-10-02 的 6 个临时失败测试属于历史缺陷复现，不能作为本次修复验收。本次仅生成规划，执行阶段需建立可重复的正式测试。

## 规划导航

- [设计与验收边界](design.md)
- [实施任务](tasks.md)
- 规格增量：[卡顿服务端](specs/jank-monitoring-server/spec.md)、[内存泄漏](specs/memory-leak-reports-server/spec.md)、[认证](specs/web-user-authentication/spec.md)、[应用管理](specs/project-management/spec.md)、[错误响应](specs/backend-api-errors/spec.md)、[卡顿网页](specs/jank-monitoring-frontend/spec.md)。
