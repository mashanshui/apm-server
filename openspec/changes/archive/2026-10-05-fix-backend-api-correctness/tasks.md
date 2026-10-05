# 后端接口正确性修复任务

本清单用于后续 apply 阶段，实施进度以勾选状态为准。范围、字段及失败语义以[提案](proposal.md)、[设计](design.md)和六份规格增量为准；每组完成自身测试及文档后再推进集成验收。

## 1. 登录会话与 CSRF

- [x] 1.1 在 AuthenticationController/SecurityConfig 中接入统一认证后策略，成功登录时轮换已有 Session ID 并刷新 CSRF Cookie；扩展 AuthenticationApiIntegrationTests，验证已有/无会话登录、失败登录不保存认证身份和登录入口仍要求 CSRF。
- [x] 1.2 补齐生产 Spring Session JDBC 依赖与启用配置，核验 Cookie 属性及超时；增加启用 Spring Session JDBC 的真实本地 HTTP/PostgreSQL 测试，用独立 Cookie 容器验证旧 Session ID 未认证、新 Session 可恢复、登录后首个写请求成功、新 Cookie 配旧 CSRF 头失败及退出失效；测试不能只复用同一个 MockHttpSession。
- [x] 1.3 检查前端 authApi、session store 和统一 HTTP 客户端的 Cookie 读取行为，添加登录后立即编辑应用的回归，必要时只调整 Token 刷新衔接；运行对应 Vitest 测试验证不增加 Token 持久化。
- [x] 1.4 同步 docs/api/app-api.md、根知识库安全主题、后端认证主题及前端 API/状态主题，写明会话与 Cookie CSRF 的实际边界；校验示例与 1.1～1.3 的测试结果一致并检查链接。

## 2. 框架请求错误契约

- [x] 2.1 为 ApiExceptionHandler 增加正文解析、参数绑定/类型、缺失项、Bean/方法参数校验的明确映射；新增正式测试覆盖非法 UUID、limit=abc、缺失 Worker 名称、坏 JSON、缺失正文/必填项，断言 400、稳定 code、JSON 字段及没有业务副作用。
- [x] 2.2 补齐框架 405/415 响应，受控字段 errors 限制为 50 项且不回显敏感值；测试 Allow 头、上传缺失 part、密码/Token/租约输入、专项业务 415/413、Retry-After、CSRF 和批次部分接受，确认既有错误不被覆盖。
- [x] 2.3 增加真实 HTTP 错误冒烟测试，分别覆盖网页、Agent、Worker 的有效认证下框架失败及未认证失败，验证容器 /error 分派不替换既定响应；按实际失败类别断言 JSON 与状态，不把所有异常统一判为 400。
- [x] 2.4 在 docs/api/ 内集中记录框架错误表并从 README、app-api、agent-query-api、analysis-api 链接；同步后端认证/测试主题，核对客户端 multipart 错误说明并记录需修改或无影响的结论，执行本地链接检查。

## 3. 应用 PATCH 部分更新

- [x] 3.1 将 AppUpdateRequest 改为明确记录 name/description 是否出现的 DTO，在事务内只更新出现且合法的字段；扩展 AppApiIntegrationTests 验证名称单独更新、描述单独更新、null/空白清空描述和遗漏字段保留。
- [x] 3.2 实现空对象及规范化后未变化请求不更新时间；回归 name=null/空白、错误 JSON 类型、未知/身份字段、多字段非法时原子拒绝、Owner/Admin 与 Developer/Viewer 权限、CSRF 和重复请求的 updatedAt 行为。
- [x] 3.3 调整 frontend/src/types/app.ts 的可选字段和可空 description，核对 AppSettingsView 的保存逻辑；添加字段遗漏/清空及完整表单保存测试，运行相关 Vitest 和 npm run typecheck。
- [x] 3.4 同步 docs/api/app-api.md 的部分更新示例、后端实现及前端 API/状态主题，记录不兼容的省略描述语义和无操作请求；用测试中的请求/响应核对示例并检查链接。

## 4. 卡顿完整统计与数据库分页

- [x] 4.1 调整 JankAggregationRepository、JankQueryService 及内存实现，建立总览、趋势、Issue 页和事件页查询操作；用 JankDatasetStatisticsTests/JankQueryServiceTests 验证全范围计数、去重、精确/估算分位数、空值及奇偶样本秩规则，保持详情读取独立。
- [x] 4.2 实现 ClickHouse 总览/趋势/Issue 聚合和事件摘要查询，仅在最终分页结果上使用 limit+1，接入 QueryBudget；增加真实 ClickHouse 对照测试，至少覆盖 120 条事件、60 个 Issue、单 Issue 超过 50 条事件和 limit=1/20/50，断言小页不改变统计输入且摘要不加载完整采样 JSON；真实数据库覆盖完整耗时和合法零值，缺失值由 4.1 内存参考测试覆盖。
- [x] 4.3 增加 Jank 自有版本化游标及完整排序条件，覆盖场景/算法/应用/指纹/时间等绑定；测试静态数据全量遍历不重不漏、并列排序、续页恢复默认时间窗、跨入口误用、旧格式/损坏/超长游标、非列表 cursor 拒绝及空末页。
- [x] 4.4 验证查询客户端的受限响应正文读取及截止时间，必要时补齐平台实现并回归 Crash；用受控慢响应和降低预算的 ClickHouse 测试分别断言 408/422/503，覆盖超时、扫描量、数据库内存、响应字节及禁止部分成功。
- [x] 4.5 对 10 万卡顿事件执行可重复性能检查，保存数据分布、数据库版本/资源、EXPLAIN、扫描行/字节、峰值内存、响应字节及耗时；对比小页与大页，明确实际成功规模和超限行为，结果记录到后端知识库并从 README 索引。
- [x] 4.6 同步 docs/api/jank-server-api.md、agent-query-api.md、docs/jank-fixed-dataset.md、根查询/待确认主题及后端查询/测试主题；只在 4.2～4.5 证据支持时更新截断限制说明，保留历史记录并校验文档链接。

## 5. 内存泄漏查询下推

- [x] 5.1 增加报告问题页/趋势查询操作及内存参考实现，把桶数量校验放到存储访问前，页偏移使用 long；单元测试覆盖 2000/2001 桶、page=2147483647、超出整数范围、空页和同时间代表路径选择。
- [x] 5.2 在现有 ClickHouse FINAL 报告事实上实现路径展开、字面关键词筛选、每报告每 signature 去重及全范围聚合；真实数据库测试对照 A=3/B=1 固定数据、重复路径/报告、空路径、跨应用、多设备/版本、时间边界和特殊字符，验证不读取 report_json。
- [x] 5.3 实现一次查询返回全范围总计及分页行，保持最新完整路径、versions、排序和占比分母；用超过 100 个问题的数据验证不同 pageSize、所有 sort/order、超过末页保留总计及引用链/版本集合不被静默截断。
- [x] 5.4 实现数据库趋势聚合并仅在 Java 补齐有界空桶；接入服务端默认预算，测试 408/422/503、超量桶不访问仓储、完整路径超过结果预算明确失败及空结果与存储失败区分。
- [x] 5.5 对至少 1 万份多路径报告执行可重复性能检查，记录 EXPLAIN、扫描量、数据库峰值内存、响应字节、耗时和 Java 接收数据量；验证小页不接收全部报告正文，明确成功与超限规模，性能记录从后端知识库 README 索引。
- [x] 5.6 同步 docs/api/memory-leak-reports-api.md、agent-query-api.md、docs/memory-leak-fixed-dataset.md、根查询/待确认主题及后端查询/测试主题，说明预算、页码、代表路径和口径；核对客户端上传流程无变化并检查链接。

## 6. 卡顿网页与 Agent/MCP 适配

- [x] 6.1 在 createJankCursorQuery、卡顿 Issue/事件 composable 和页面增加 INVALID_CURSOR 显式恢复；Vitest 验证不透明游标透传、失效后重新查询替换列表、筛选切换隔离旧响应以及续页时间窗不漂移。
- [x] 6.2 完善卡顿区域的 QUERY_TIMEOUT/QUERY_RESOURCE_LIMIT 显示及缩小范围提示；测试失败不显示为空数据且其他成功区域保留，执行真实本地页面检查并保存可见变化截图，区分模拟 API 与真实后端结果。
- [x] 6.3 扩展 AgentQueryApiIntegrationTests 与 MCP 工具测试，验证同一静态数据筛选下统计一致、页大小限制保持、MCP 使用首次 query.from/to 完整续页，以及 400/408/422/503 正确传播；按 mcp-server/package.json 执行 npm test 和 npm run typecheck。
- [x] 6.4 同步前端路由/交互、API/状态及测试知识库，更新 docs/api/mcp-api.md 的游标时间窗示例和错误处理；保留 MCP 续页要求显式时间的现有约定，检查所有示例与 6.1～6.3 一致。

## 7. 跨模块集成验收

- [x] 7.1 运行 bash ./backend/gradlew -p backend test，以及 frontend 的 npm test、npm run typecheck；核对 Java 21 toolchain、Docker 数据库专项测试确实运行，汇总通过/失败/跳过和已知 Rhea 摘要问题，不能把跳过或既有失败记为全绿。
- [x] 7.2 执行本地真实 HTTP 综合链路：登录及旧会话拒绝、首个 PATCH、卡顿跨页与详情、报告分页/趋势、错误正文及 Agent 隔离；将其与数据库/性能证据对照，验证上报、FPS、挂起率、内存采样、符号表和分析控制面未受回归影响。
- [x] 7.3 汇总根/后端/前端知识库的当前实现与验证边界，明确 API 及客户端文档实际同步文件和无影响项；执行 Markdown 链接检查、git diff --check 和 openspec validate fix-backend-api-correctness --strict，确认规格场景都有对应证据，保留云端部署与生产容量的独立边界。

## 2026-10-05 后续授权云端验收

用户另行授权“验证新制品后更新锁定值”及“部署云端测试”。Rhea 来源/内容/行为校验后更新摘要，完整后端构建通过；现有云端 HTTP 环境已发布本变更，真实 JDBC 重启、ClickHouse、Agent/MCP 和登录后的浏览器页面验收通过，详见[云端记录](../../../../docs/backend-api-cloud-validation.md)。本补充不改写此前本地验收的操作范围，不将有限合成样本记为生产容量或 HTTPS 通过；任务保持 31/31 完成，尚未归档。

## 2026-10-05 正式规范同步与归档

用户授权同步归档后，六个能力的增量已落入正式规范：新增三个需求、修改四个需求，原有其他需求及场景保留；19 个正式规范严格校验通过，任务 31/31 完成。变更归档于 `2026-10-05-fix-backend-api-correctness`，连同 `.openspec.yaml` 保留。前节“尚未归档”表示同步前的历史状态，当前已完成归档。
