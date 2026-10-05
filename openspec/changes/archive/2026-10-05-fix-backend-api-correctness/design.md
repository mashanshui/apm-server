# 后端接口正确性修复设计

## 现状与约束

动机及范围见[提案](proposal.md)。2026-10-04 已读取正式规格、控制器、查询服务、仓储、前端调用及相关测试；下表为代码事实，不代表修复已经实现。

| 问题 | 当前入口与实现 | 可复用基础 |
|---|---|---|
| 卡顿统计截断 | `ClickHouseJankQuerySql.selectEvents` 对原始事件执行 LIMIT，`JankQueryService` 再聚合及分页 | Crash 查询仓储的分投影聚合、查询绑定游标和预算 |
| 报告全量读取 | `ClickHouseMemoryLeakReportRepository.findAll` 返回 report_json/gc_paths_json 全文，`MemoryLeakQueryService` 在 Java 中聚合 | 现有 FINAL 报告事实、查询预算和内存参考口径 |
| 会话未轮换 | `AuthenticationController.login` 调用 authenticate 后直接 saveContext | 现有 Spring Security、Spring Session JDBC、Cookie CSRF |
| 错误响应缺口 | `ApiExceptionHandler` 覆盖业务异常，缺少框架绑定/校验分支 | `ApiErrorResponse`、统一前端错误读取 |
| PATCH 覆盖 | `AppUpdateRequest` 的 nullable record 无法区分缺失和显式 null，服务直接覆盖两个字段 | `AppInputValidator`、角色校验和事务 |

正式卡顿及内存规格要求完整统计，当前截断/全量读取属于待修复实现差距；文档已注明这些限制，实施通过后才更新“当前实现”。卡顿旧游标和 PATCH 省略字段行为属于本变更明确列出的不兼容行为调整。新旧格式不并行维护。

## 目标与边界

- 各领域保留自己的查询接口和模型，只复用平台现有资源预算；不把领域内部 Crash 类直接作为 Jank 公共依赖，也不先建立通用查询框架。
- 生产适配器仅向 Java 返回聚合、分页摘要及当前页需要的完整路径；内存适配器保留为小规模确定性参考，不能作为生产查询性能证据。
- API 字段和业务统计公式按规格保留；安全 Cookie 的 HTTPS 属性继续由现有部署入口约定管理。
- 本次不迁移数据库、不新增预聚合表、不回填报告、不更改上报和分析任务状态机。后续如需索引或物化视图，必须由性能证据支持并另行规划。

## 设计决策

### 1. 卡顿按用途查询，数据库返回完整统计

将 `JankAggregationRepository.find` 的生产查询用途替换为总览、趋势、Issue 页、事件页四类操作；保留单事件详情操作。`JankQueryService` 负责规范化参数、构造游标及组装响应，聚合放在适配器内。ClickHouse 从 `apm_jank_event FINAL` 的逻辑事件读取，先限定应用、时间和白名单维度，再进行聚合。

- 总览/趋势：数据库计算事件数、设备及会话去重、可归组数和精确消息耗时分位数，不使用列表 limit 裁剪输入。空集合保持 no_data/null；趋势继续按现有 hour/day 桶及现有非空桶策略返回。
- Issue：排除空指纹，按指纹聚合完整数据；精确消息耗时与估算耗时分别按有效值计算。缺失估算不得按零填充。代表性的场景、算法和指纹版本按最早 `(event_time,event_id)` 取值，避免字段来自不同记录。
- 分位数：固定采用 `max(1, ceil(N*p))` 的升序秩，p 为 0.5/0.9/0.99；SQL 选择能通过确定性奇偶样本测试的精确实现，禁止用插值或近似算法改变既有数值口径。
- 事件页：只投影 `JankEventSummary` 必需的标量列，不读取 jank_payload_json/jank_analysis_json；完整载荷仅详情读取。
- 页查询在最终排序和游标条件之后取 `limit+1`，多一条仅用于判定下一页。Issue 排序元组为 `(eventCount DESC,lastSeenAt DESC,fingerprint ASC)`，事件为 `(occurredAt DESC,eventId ASC)`。

2026-10-04 用户确认保留现有表结构：耗时列为非空 UInt64，当前写入必须具有完整分析对象，因此真实数据库验证所有当前可存储耗时及合法零值；内存参考额外验证缺失耗时不按零填充，不新增 Nullable 列、有效性标志或迁移。

曾考虑提高原始 LIMIT 或新增截断标记；前者仍会丢数据，后者不能满足完整统计目标。直接沿用现有事实表和领域仓储即可完成本轮修复。

### 2. 卡顿游标绑定实际查询上下文

在 Jank 域增加版本化游标，采用 Crash 已验证的设计原则，包含列表种类、绝对 from/to、筛选摘要及排序元组。摘要覆盖 appId、fingerprint、appVersion、channel、environment、osVersion、deviceModel、scene、algorithmVersion 和列表排序。游标不是授权凭据，所有请求继续进行权限校验和 SQL 转义。

首次省略时间使用最近 24 小时；HTTP 续页省略时间从游标恢复。显式时间与游标不符或筛选变化返回 INVALID_CURSOR，非法版本、格式、长度及排序值同样拒绝。页大小可在相应入口上限内变化；总览/趋势不使用游标。Jank 自有游标长度上限采用 2048 字符，避免继续受普通筛选 256 字符上限错误限制。

前端 `createJankCursorQuery`、`useJankIssues`、`useJankIssueEvents` 原样传递游标，捕获 INVALID_CURSOR 并提供重新查询；筛选变化清空分页状态。MCP 的 `prepare` 当前要求续页显式传入首次响应的 from/to，这一工具约定保留，回归测试用 query 中的绝对时间继续请求，不能把 HTTP 的省略时间能力误写为 MCP 已支持。

静态数据集保证完整遍历；持续写入下 Issue 排名可能变化，沿用 Crash 的非快照边界，不新增快照存储。

### 3. 内存泄漏在现有报告表上展开引用链并聚合

为 `MemoryLeakReportRepository` 增加按问题页和趋势返回结果的查询操作，或者在同域分离最小查询端口；上报幂等读取和附件清理操作保持各自用途。生产查询不再调用返回完整报告列表的 findAll。

SQL 分阶段执行：

1. 从 `apm_memory_report FINAL` 按 appId、UTC 时间、版本、设备、进程等列筛选逻辑报告，仅投影聚合需要的标量和 gc_paths_json，不读取 report_json/附件正文。
2. 在数据库内展开 gc_paths_json；signature 精确筛选，keyword 在 signature、gcRoot、leakReason、reference、referenceType、declaredClass 上进行区分大小写的字面子串匹配。使用类型化参数或现有统一转义，百分号和下划线不作为通配符。
3. 按 `(eventId,signature)` 去重，空路径不贡献次数；instanceCount 不加权。匹配路径生成全部问题数、总发生次数、全部设备去重数及各 signature 的聚合。
4. 最新代表路径按 occurredAt 降序、eventId 升序决定，整组 path/reason/root/class 来自同一条记录。versions 在数据库内完整去重排序，不先截断版本或路径。
5. 问题页采用一个查询语句返回总计与分页行，例如带类型标记的总计行加分页行，保证空页仍有总计，避免多个请求分别计算分母和当前页。OFFSET 使用 long 安全计算，只有最终问题行按 pageSize 截断。
6. 趋势在数据库内按 UTC 桶聚合次数及设备集合；Java 仅对不超过 2000 个桶补零。桶数量校验提前到存储访问之前。

现有表排序键为 `(app_id,event_id)`，时间范围查询仍可能扫描较多数据；资源预算必须生效，EXPLAIN 和查询日志记录扫描量。新增明细表需要双写和历史回填，当前无必要，因而不采用。

### 4. 复用统一查询预算并保持失败原子性

两类查询使用 `QueryProperties` 和 `ClickHouseHttpClient.executeQuery`：当前默认执行时间 2000 ms、扫描 500 万行/512 MiB、数据库内存 256 MiB、HTTP 结果 8 MiB。卡顿保留 timeoutMs 可选参数及最大 5000 ms；内存泄漏沿用现有查询白名单，服务端直接使用默认预算。

总览、趋势、问题页各以一次有界数据库执行完成；客户端读取同样有字节和截止时间限制。确认现有流式读取的截止时间覆盖响应正文；若现有实现只约束响应头，则在平台客户端补齐受限正文读取并回归 Crash 查询。失败使用现有 408/422/503 语义，禁止降级返回“目前算出的部分结果”。

内存报告每条路径和版本集合可能很大，当前页超过响应上限即报 422，不隐式缩小字段或改变列表展示含义。数据库聚合可能仍需要较多内存，此时由数据库预算失败而非转移为无界 Java 内存占用。

### 5. 登录使用完整认证后会话处理

2026-10-04 实施差异：真实 HTTP/PostgreSQL 测试确认原依赖只有 `spring-session-jdbc`，没有 Boot 4 的会话自动配置模块或显式启用入口，上下文缺少 `JdbcIndexedSessionRepository`；原文档中的 JDBC 会话描述不能作为已实现证据。用户已确认在本变更中补齐生产装配：改用 `spring-boot-starter-session-jdbc`，生产启用 JDBC、普通 H2/MVC 测试禁用，并核验 Cookie 属性、会话超时和旧标识失效。使用既有 Flyway 会话表，不新增迁移。

在手动登录控制器中接入与网页安全链一致的 `SessionAuthenticationStrategy`，认证成功后、保存 SecurityContext 前执行已有会话 ID 轮换及 CSRF 认证后处理。不要假设 `sessionManagement` 配置会替手动控制器自动执行此流程。

成功后通过现有 CookieCsrfTokenRepository 生成并保存新的 CSRF 值，使成功响应直接提供可用 Cookie；前端继续在每次写请求前读取 Cookie，不增加 Token 存储。验证新 Cookie 与旧请求头不匹配时拒绝。Cookie CSRF 模式不提供服务端所有历史 Token 的撤销注册表，本变更不承诺任意重放旧 Cookie+旧头组合的集中撤销。

验证同时覆盖 MockMvc 与启用 Spring Session JDBC 的真实本地 HTTP：两个独立 Cookie 容器分别持有旧、新会话 ID，旧 ID 返回未认证，新 ID 可恢复用户；确认 PostgreSQL 会话记录迁移后不会出现旧 ID 别名。登录失败不得保存认证上下文，成功登录后的首个写请求和退出均通过回归。

曾考虑仅手动调用 changeSessionId；统一认证后策略可以集中处理会话与 CSRF，减少遗漏。

### 6. 框架错误接入现有响应类型

在现有 ApiExceptionHandler 中添加明确的 MVC 异常处理，覆盖 HttpMessageNotReadableException、类型转换、缺失参数/头/part、绑定错误、MethodArgumentNotValidException、HandlerMethodValidationException，以及适用的约束校验异常。不增加包揽所有 Exception 的 400 处理器。

| 类别 | HTTP / code | 字段详情 |
|---|---|---|
| 正文缺失、语法/解码错误 | 400 / INVALID_REQUEST_BODY | 使用固定说明，不输出解析器原始异常 |
| 路径/查询/头类型错误、缺少必填项 | 400 / INVALID_PARAMETER | 可安全定位时返回 field/code/message |
| 已解码字段或方法参数约束失败 | 400 / VALIDATION_FAILED | 返回受控字段说明 |
| 方法不支持 | 405 / METHOD_NOT_ALLOWED | 保留 Allow |
| 媒体类型不支持 | 415 / UNSUPPORTED_MEDIA_TYPE | 保留相关框架头 |

`@ModelAttribute` 的绑定结果若包含 typeMismatch，即归入 INVALID_PARAMETER，不能仅按异常类名把非数字 limit 错归为字段约束。errors 最多 50 条，只取声明字段名和受控消息；未知 JSON 属性不直接回显客户端给出的属性名或值。保留 requestId 当前可空约定，避免顺带引入链路追踪系统。

已有业务异常处理优先，尤其 AppNotFound、403、413、业务 415、查询预算和 Retry-After。安全过滤链的认证/CSRF 失败保留原处理器；SDK 部分接受响应不改为整个批次失败。真实 HTTP 测试还要确认 /error 分派不会再次覆盖响应，不能只凭 MockMvc 的空正文行为推断容器行为。

### 7. PATCH 用最小字段存在性表示

将 AppUpdateRequest 调整为只包含 name/description 及字段存在标记的更新 DTO，setter 记录是否出现；对非字符串 JSON 值显式拒绝，避免默认强制转成字符串。继续拒绝未知和身份字段，不引入通用 JSON Patch 框架。

事务内先校验权限、加载应用，分别规范化已提交字段，再一次应用变更。缺失使用实体原值；description 的 null/空白清空，name 的 null/空白非法。空对象及规范化后未变化的请求直接返回当前 AppResponse，不调用更新时间逻辑。前端类型改为 `name?: string`、`description?: string | null`，设置页可继续提交完整表单，但不得把未编辑字段自动替换成空值。

采用已确认的部分更新方案；PUT 完整替换方案不实施。并发写同一字段仍沿用现有事务的后写覆盖边界，不扩展乐观锁协议。

## 风险与取舍

- 精确去重、分位数及 JSON 展开成本较高 → 预算内精确返回；超限明确失败，通过实际数据规模和 EXPLAIN 测量，不承诺未测生产容量。
- SQL 改写易改变匹配、空值和代表路径 → 使用内存参考及固定数据逐项对照，特别覆盖重复 signature、同时间报告、空路径、跨应用和边界时间。
- 升级后浏览器持有旧游标 → 返回 INVALID_CURSOR，网页显式重新查询；发布时后端与前端配套。
- 会话测试只用 MockHttpSession 可能漏掉持久化行为 → 真实 PostgreSQL/Spring Session HTTP 验证旧 Cookie，不用同一个可变 Mock 对象代替旧标识攻击测试。
- 通用异常处理可能吞掉业务错误 → 保留既有处理优先级，以错误码、状态、头和副作用断言回归。
- 大页码和超量趋势可能在校验前耗费资源 → 存储前校验整数和桶数，OFFSET 用 long，事件扫描始终受服务端预算限制。

## 验证设计

1. 正式回归覆盖此前六个临时缺陷场景，并补充会话失效、CSRF、部分更新权限/原子性/无操作请求。历史临时文件不作为测试依赖。
2. 真实 ClickHouse 容器采用仓库固定版本，创建真实相关表结构。卡顿至少 120 条事件及 60 个 Issue，另设单 Issue 超过 50 条事件；比较 limit=1/20/50 的计数、分位数、排序及全量遍历。奇数、偶数、单样本与合法零耗时由真实 ClickHouse 覆盖；缺失耗时由内存参考测试覆盖。
3. 报告固定数据覆盖文档的 A=3/B=1、重复路径及全范围分母，增加超过 100 个问题、多设备、多版本、同时间代表记录、特殊字符筛选、空页及超大页码。
4. 降低预算分别触发扫描、内存、时间、响应字节上限；验证不会返回部分成功，趋势超 2000 桶不调用仓储。用受控慢响应验证正文截止时间。
5. 性能记录至少包含 10 万卡顿事件、1 万内存报告及多路径数据，说明生成分布、CPU/内存、数据库版本、EXPLAIN、扫描行/字节、峰值内存、响应字节和耗时；同规模不同页大小对照。有效查询须在预算内完整成功或明确报超限，不以超限结果声称该规模可查询。记录可成功查询的实际规模边界。
6. 网页/Agent 同范围结果一致，MCP 使用首次绝对时间续页；网页验证 INVALID_CURSOR 恢复、408/422 区域错误和登录后首个编辑。真实页面变更保留截图证据。
7. 后端全套 test，前端 npm test/typecheck；MCP 契约回归按其 package.json 执行。构建使用项目 Java 21 toolchain 与本机可用 JDK，不照搬其他系统路径。若复现文档记载的既有 Rhea 制品摘要失败，单独列出原因及受影响范围，不将失败或跳过写成全绿。

## 发布与回退计划

本变更实施验收限定本地及受控容器，云端发布不包含在当前授权中。后续发布需配套后端、前端并更新 API 文档；无数据迁移或回填。已有网页会话在下次成功登录时进入新轮换流程，旧格式游标通过重新查询恢复。若回退，需配套回退应用版本并清空页面分页状态；回退将重新引入本提案列出的行为缺口，必须记录，不能把错误统计解释为正常降级。

## 文档同步归属

实施时按事实更新下列文件，规划阶段不改写“当前实现”：

| 类别 | 文件与内容 |
|---|---|
| 根知识库 | `docs/knowledge-base/00-当前实现与验证边界.md`、`05-查询与Dashboard.md`、`06-安全与隐私.md`、`08-测试与质量保障.md`、`13-待确认事项.md`：修复状态、统计及安全边界和验收证据 |
| 后端知识库 | `backend/docs/knowledge-base/00-当前实现与验证边界.md`、`02-接收解析与查询链路.md`、`04-认证授权与安全实现.md`、`06-测试与质量保障.md`；新性能记录在本目录并从 README 索引 |
| 前端知识库 | `frontend/docs/knowledge-base/03-页面路由与交互.md`、`04-API数据模型与状态管理.md`、`06-测试与质量保障.md`：游标恢复、部分更新类型和 CSRF 联调 |
| API | `docs/api/app-api.md`、`jank-server-api.md`、`memory-leak-reports-api.md`、`agent-query-api.md`、`mcp-api.md`；统一框架错误集中在 `docs/api/` 并从 README 和相关页面链接 |
| 客户端 | 核对 `docs/client-integration/` 中 multipart 缺失/类型错误的说明；已发布专项错误保留，未受影响的接入步骤明确记录无需修改 |

新增性能/错误文档必须从所属 README 及相关主题可达，所有本地 Markdown 链接需校验。正式规格只在后续同步/归档阶段更新，本次只写规格增量。
