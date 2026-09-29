# 应用查询 Token 与 TypeScript MCP 技术设计

## Context

动机与范围见 [提案](proposal.md)。本次仅生成规划，以下均为待实施设计。

当前后端为 Java 21/Spring Boot 4.1 模块化单体，网页查询由 Controller 校验 Session 和应用成员关系，跨模块仅能依赖 api。CrashQueryPort 只有 findAll 与 findByEventId；ClickHouse 实现的 findAll 仅筛选应用和事件类型，并关联完整堆栈，之后在 JVM 筛选、聚合和分页。公共 HTTP 客户端固定 10 秒超时，与 API 查询预算脱节。原始表按月份分区，排序键包含应用、事件类型、时间、事件 ID，首轮可利用现有表下推。

旧 apm-agent-platform-design.md 将共享 MCP 放在后续。本次用户明确选择先做外部 Agent 只读查询，是对交付顺序的调整；不代表旧方案的 Worker、源码关联、证据冻结或修复能力已实现。根知识库仍描述网页 Session 查询，实施后应明确新增独立 Agent 入口，而非覆盖网页约定。

## Goals / Non-Goals

目标：一个应用 Token 可由 MCP 和其他查询客户端复用；TypeScript 只适配协议，Java 是身份、查询口径与数据隔离的唯一权威；网页与 Agent 共用业务查询实现。

非目标：通用代理、模型调用、持久 MCP 会话、任务调度、源码访问、报告快照、缓存、消息队列和预聚合建设。首版采用单实例、有界同步查询；不以协议通过代替模型根因定位验收。

## Decisions

### 1. 独立 TypeScript 工程与进程

采用根目录 mcp-server，与 backend/frontend 并列，不建立无需求的 monorepo 工具链。使用官方 TypeScript MCP SDK、严格类型、锁文件及独立容器。具体 Node LTS、SDK 和协议版本在第一项互通验证中精确记录，不使用未核实的“最新版”或假定所有客户端兼容。

建议结构：src/index.ts、config.ts、server.ts；transport/http.ts；auth/query-token.ts；apm/client.ts、contracts.ts、errors.ts；tools/application.ts、crash.ts、jank.ts、memory.ts；tests/、package.json、package-lock.json、tsconfig.json、Dockerfile、.env.example、README.md。工具文件内维护对应 schema 与转换，不先抽象插件注册框架。

选择独立进程符合用户指定的 TypeScript；相较 Java 内嵌方案多一次 HTTP 跳转，但无需跨语言共享数据库或业务计算。MCP 无数据库账号，无模型 API Key。

### 2. 应用 Token 模型与管理

identity 新增查询 Token 表，以新增 Flyway 版本迁移。字段：id、app_id 外键、name、token_digest 唯一索引、display_prefix、scope（固定 apm:read）、created_by、created_at、expires_at、revoked_at。随机秘密至少 256 bit，使用独立格式前缀，例如 apm_qt_，保存 SHA-256 摘要；高熵随机凭据不采用可逆加密或复用 Android App Key。列表状态由到期时间与撤销时间推导，不另存冗余 status。创建者仅审计，不动态继承其其他应用或角色。

管理 API：

| 方法与路径 | 请求/响应 |
|---|---|
| POST /api/v1/apps/{appId}/query-tokens | name（1—100 字符）、expiresInDays（30/90/365，默认 90）；201 返回元数据及唯一一次 token |
| GET /api/v1/apps/{appId}/query-tokens | 按创建时间和 ID 倒序分页，默认 20、最大 100；只返回元数据及派生状态 |
| DELETE /api/v1/apps/{appId}/query-tokens/{tokenId} | 软撤销，204；同应用重复撤销幂等，不存在或跨应用统一 404 |

三者仅 OWNER/ADMIN；POST/DELETE 保留 Session CSRF。禁止使用 Bearer 管理 Token。名称可重复；重复点击由前端在途禁用，创建请求不自动重试。提交成功但响应丢失时刷新列表并撤销不确定项，再生成新值，不保存明文用于找回。每个应用有效 Token 默认最多 20 个，通过事务锁应用记录避免并发绕过；上限是初始运行配置，可后续调整。

前端在现有设置页增加管理区域，包含加载、空、错误、创建中、展示、复制失败、撤销确认及撤销失败状态。创建响应 Cache-Control: no-store；完整值只存当前组件，关闭、卸载、切换应用和 401 时清除。使用取消与请求代次拦截迟到响应；列表只显示前缀。普通成员不显示管理操作，服务端仍强制授权。

### 3. Java 统一查询入口与认证边界

增加 /api/agent/v1，只支持 GET 的查询白名单。无 appId 路径：GET /application 返回 Token 所属应用的 id、name、packageName；其余沿用业务路径后缀。Bearer 必须每次从 PostgreSQL 校验，禁止认证成功缓存。独立无 Session 安全链只作用于该路径；不修改网页链和 ingest 链。Cookie 不参与该链认证，不能作为 Bearer 缺失时的回退。

新增 agentquery 适配模块，依赖 identity、crash、jank、memory 的 api；必要的查询接口及命令类型从业务模块公开，内部实现仍在本域。网页 Controller 和 Agent Controller 调同一查询接口，分别执行对应授权；更新 ArchUnit 白名单与无环测试。不把 Agent Principal 伪造成网页用户交给 CurrentUserService。

查询令牌推导 appId，所有查询命令强制携带该值；未知 appId 参数拒绝。凭据错误统一 401 QUERY_TOKEN_INVALID；凭据数据库不可用 503 QUERY_AUTH_UNAVAILABLE；资源不存在保留 404，参数错误 400，限流 429，查询超时 408 QUERY_TIMEOUT，扫描/内存超限 422 QUERY_RESOURCE_LIMIT，存储临时故障 503。权限或预算错误不能包装为 no_data。撤销事务完成后发起的认证必须失败；不强行取消此前已认证请求。

### 4. MCP 工具与后端映射

采用 Streamable HTTP /mcp，无业务会话状态。每个 MCP HTTP 请求先调用固定后端 /api/agent/v1/application 验证身份；工具实际查询再次由后端认证。请求上下文携带 Token，禁止全局可变 Header 和日志打印。HTTP 客户端禁止重定向，固定 origin 由可信配置提供，不允许工具指定 URL。该模式是平台自有查询凭据的受控代理，不是通用 OAuth access token 透传服务；首版客户端必须支持手工配置 Bearer，不支持者不列为已验收。

| 工具 | /api/agent/v1 下的后端 GET 路径 |
|---|---|
| get_application | /application |
| get_crash_overview、get_crash_trend、list_crash_issues | /crashes/overview、/crashes/trend、/crashes/issues |
| list_crash_events、get_crash_event | /crashes/issues/{fingerprint}/events、/crashes/events/{eventId} |
| get_jank_overview、get_jank_trend、list_jank_issues | /janks/overview、/janks/trend、/janks/issues |
| list_jank_events、get_jank_event | /janks/issues/{fingerprint}/events、/janks/events/{eventId} |
| get_fps、get_suspension_rate | /jank-metrics/fps、/jank-metrics/suspension-rate |
| get_jank_metric_trend、get_jank_dimensions | /jank-metrics/trend、/jank-metrics/dimensions |
| get_memory_summary、get_memory_trend | /memory-metrics/summary、/memory-metrics/trend |
| list_memory_leak_issues、get_memory_leak_trend | /memory-leaks/issues、/memory-leaks/trend |

合计 19 个工具。保留各领域已支持的筛选，不强行统一成支持不存在维度的万能工具。每个工具 inputSchema 禁止未知字段，时间默认最近 24 小时、最大 31 天；MCP 适配时固定为绝对 UTC 时间后提交，响应回传实际范围。后端继续二次校验。分页追加必须携带首次实际时间窗。

结构化响应组织为 data、query（有效范围和筛选）、evidence（已有事件/问题/构建/符号版本引用）、retrievedAt、truncated 和可选 continuation。不凭空填入后端未提供的版本字段。单位和统计口径在工具描述及响应 schema 中明确，保留 null、no_data、denominator_insufficient 等值；FPS 分位数方向和挂起率的秒/小时前台单位不能混淆。

默认列表 20、最大 100，最终序列化工具结果上限 256 KiB（含兼容文本副本）。统计结果超限直接报 RESULT_TOO_LARGE；有业务分页的列表可返回更小页及对应正确游标，不能删除条目后沿用跳过数据的原游标。详情默认摘要，按 section/offset/limit 读取有界堆栈或调用树片段；片段引用保留稳定节点/帧 ID，不能返回悬空字典引用。continuation 绑定事件、section 与本次内容摘要；重新读取内容变化时返回 EVIDENCE_CHANGED，要求重启片段读取，不保存跨请求还原缓存。最终字节超限仍失败，不输出半段 JSON。该片段能力只是现有证据投影，不新增 HPROF 分析。

HTTP 认证错误用 401/503，协议参数错误按 SDK 协议错误处理，工具业务错误用 isError 并返回 code、retryable、requestId 及可选 retryAfter。首版不自动重试查询，交由调用方按明确错误处理，避免预算倍增。

### 5. 查询保护与 Crash 改造

后端 Agent 入口单实例内存限流：初始每 Token 每分钟 60 次、并发 2；每应用每分钟 300 次、并发 8，均可配置。身份检查计入限额，文档说明一次 MCP 调用可能包含预检和实际查询。非法凭据另由入口/IP 限制，防止认证数据库被无效请求打满。MCP 对请求体（初始 64 KiB）、连接和后端响应流（初始 8 MiB）限界；后端大详情超过保护界限时明确拒绝，不以流切片伪装完整数据。

CrashQueryPort 按概览、趋势、Issue 页、事件页、详情划分查询契约。ClickHouse 实现将 appId、[from,to)、白名单维度和事件类型下推 WHERE；概览/趋势使用精确 distinct 条件聚合；Issue 使用 GROUP BY 指纹，事件摘要只读取原始表必需字段；只有详情查询读取堆栈。SQL 参数使用绑定或集中类型安全编码，绝不拼接未验证的排序字段。

保留逻辑 eventId 去重，不能仅依赖后台合并时机。对现有 ReplacingMergeTree 的重复键语义做固定数据验证；同 eventId 多个物理版本必须确定性选择最新 received_time 的逻辑记录，再统计。精确去重不能替换为近似函数；跨桶会话数不能直接求和为概览。指纹筛选只作用于 Crash 分子，app_start 仍按其他维度统计。空字符串存储值与 API 缺失值按照当前解析语义归一化，需用旧内存实现作对照。

事件页按 occurredAt DESC、eventId ASC，游标保存排序元组。Issue 页按 eventCount DESC、lastSeenAt DESC、fingerprint ASC，以聚合后的排序元组做续页。游标包括版本和标准化查询摘要（appId、实际时间窗、筛选、排序），不作为授权依据，始终重新校验身份。旧游标明确拒绝。首版不持久化分页快照，新增、迟到数据可能改变排行，客户端应在需要完整比较时重新查询同一范围，工具必须标明非快照。固定数据集分页严格不重不漏。

查询预算从请求传到数据库与客户端；数据库使用 max_execution_time、扫描行数/字节和内存限制，溢出模式选择 throw，不允许返回部分成功。初始最大扫描 500 万行/512 MiB、单查询内存 256 MiB，预算由服务端配置，用户只能在允许范围内缩短。现有默认 2 秒、最大 5 秒保持；使用剩余时间控制 HTTP 等待与转换，不把固定 10 秒当作查询预算。数据库限制有检查粒度，超时验收区分请求终止与后台查询回收，不能承诺严格实时中断。上游取消时终止 HTTP 等待，并验证数据库限时仍生效。错误分类不能把资源限制一律当作存储不可用。

数据库下推仍可能扫描很多数据，LIMIT 不代表扫描上限。首轮不使用小时聚合表，不改已发布迁移；根据 EXPLAIN 和 query_log 验证现有排序键的实际裁剪，必要索引/投影只能通过新的迁移及证据引入。

### 6. 测试与验收

自动化：Token 到期边界、撤销、多应用、CSRF、角色及凭据不可混用；MCP 并发身份不串用、未知参数、恶意数据、错误转换、片段变更和大小上限；Crash 精确口径、跨桶去重、重复事件、空值、指纹分母、同时间分页、游标错误和数据库资源限制。

真实数据库：PostgreSQL 验证迁移和撤销可见性，ClickHouse 验证去重/SQL/超限。使用固定种子生成两个应用及 1 万/10 万/100 万事件规模（只用合成数据），固定硬件和并发条件，记录查询 p50/p95、read_rows/read_bytes、结果大小、JVM 峰值内存和 query_log。小时间窗不再向 JVM 传输全部历史数据，统计结果与参考实现一致；若初始限额导致拒绝，明确报告而非更改统计或隐藏失败。记录达成的容量，不把合成数据结果宣称生产 SLA。

真实客户端：使用一个明确支持配置 Bearer 的目标 MCP 客户端，锁定名称与版本；验收发现、应用、三领域查询、无数据、跨应用、撤销、截断和断连。客户端缺失时集成 SDK 测试只能证明协议测试通过，客户端验收任务不得勾选。模型回答质量不作为本次协议交付已通过的隐含结论。

## Risks / Trade-offs

- Token 可被持有人复制 → 应用范围、有限期限、撤销、最小返回与无明文日志；当前 HTTP 调试接入会明文传输 Bearer，须限制在受控环境，受信 HTTPS 接入列为后续生产化任务。不支持任意客户端 OAuth 自动登录。
- 每次访问数据库鉴权及 MCP 预检增加延迟 → 首版接受成本换取即时撤销，不引入缓存；压测记录认证开销。
- 巨大详情影响 Node 和 Java 内存 → 后端响应与最终结果分别限界，拒绝超限；片段续读不保证源数据永不变化。
- SQL 优化改变统计 → 以固定数据及参考实现逐接口对照，不先截断样本、不复用有错误去重语义的预聚合表。
- 单实例配额不能跨副本合计 → 首版部署固定一个后端查询实例；扩容前重新设计全局额度，不提前引入 Redis。
- 原方案与新方向不一致 → 实施同步知识库和旧方案说明，清楚记录只读 MCP 提前与修复平台未实施的区别。

## Migration Plan

1. 完成依赖/客户端互通验证并锁定版本，再实现数据库迁移、统一身份、查询公开接口和测试。
2. 完成 Crash 下推及资源限制；网页回归通过后接入 Agent 查询，部署初始开关关闭。
3. 发布前端管理区域、MCP 容器、代理配置；先本机合成数据完成 HTTP 联调。MCP 不强制检查 HTTPS，受信 HTTPS 环境验收列为后续生产化任务。新增远程部署须在后续实施请求的授权范围内执行。
4. 开启 Agent 查询和 MCP，创建测试应用 Token，验证三类查询、直接 HTTP 复用和撤销后拒绝；删除/撤销测试凭据。
5. 回滚优先关闭 Agent 入口和 MCP、隐藏创建操作，保留 Token 元数据与撤销管理；不删除迁移、不恢复已撤销凭据。若回退旧 Crash 实现，维持外部 Agent 禁用，防止重新暴露全量扫描。

## 文档同步清单

- 根知识库：README、00 当前边界、02 架构、05 查询、06 安全、07 部署、08 测试、09 路线图；同步根 apm-agent-platform-design.md 的交付顺序。
- 后端知识库：README、架构专题、04 认证授权、05 构建运行、06 测试；新增 Crash 查询性能证据专题并加入导航。
- 前端知识库：README、03 页面、04 API 状态、06 测试、07 安全部署，记录 Token 一次展示及清理行为。
- API：docs/api/README.md，新建 query-token-api.md、agent-query-api.md、mcp-api.md；更新 crash-api.md 的游标和资源错误。
- 客户端：Android 采集、上传和重试不变，不新增 SDK 接入步骤；检查 docs/client-integration/README.md 交叉引用并明确上报 Key 不可用于查询。
- MCP：工程 README 记录配置、启动、版本矩阵及占位凭据示例，不重复完整 HTTP 契约。

## Open Questions

无待决的产品范围问题。实施时需要采集并记录：目标客户端的精确版本、所选 SDK/Node 版本、部署硬件和性能实测值；这些是验收证据，不改变本方案范围。当前 macOS 环境无法使用规则给定的 Windows JBR 路径，构建时须定位 Android Studio 对应 Java 21 JBR 并记录 JAVA_HOME，不默认使用不兼容 JDK。
