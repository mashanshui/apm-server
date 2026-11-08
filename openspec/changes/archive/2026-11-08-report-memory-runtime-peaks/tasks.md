# 服务端任务清单

执行顺序：**阶段 S（本文件1..5）→ 阶段 C（Performance 同名变更1..5）→ 阶段 J（两边第6组）**。按实际实现和验证逐项勾选；未满足全部验证条件的任务保持未完成。每次 apply 只执行本仓库已满足前置条件的项；完成S即可交接，不能自动跨仓库实施或提前归档。

共同协议、固定样例和J1..J7见 [design.md](design.md)；[客户端任务](../../../../../../AndroidStudioProjects/Performance/openspec/changes/archive/2026-11-08-report-memory-runtime-peaks/tasks.md) 为外部检出依赖。协议变化须同步两边规划及样例，不能单边修改字段后继续实现。

## 1. 阶段 S：批量峰值协议与校验

- [x] 1.1 将ingest信封、严格字段/类型校验及领域分发切换为memory_peak_summary/memoryPeakSummary，拒绝旧类型和载荷混用；在MemoryMetricsApiIntegrationTests及相关Schema测试验证合法共同事件、未知字段、类型错误与批内合法/非法事件边界。
- [x] 1.2 实现原身份/版本、三项独立峰值、起止范围、非负安全整数及occurredAt=lastSampledAt校验；用MemoryMetricsTests覆盖零/null、三项不同上下文、空轮、越界、非法processId、App Key/包名错误，证明其他领域的信封和鉴权未改变。
- [x] 1.3 增加峰值专属历史窗口（默认90天），保留公共未来容差及其他事件原窗口；用可注入时钟覆盖8天、90天前1毫秒、恰好90天、未来容差、长运行早期峰值与其他事件7天边界。
- [x] 1.4 同步docs/api/memory-metrics-api.md、docs/client-integration/memory-metrics.md、docs/knowledge-base/03-事件模型与数据存储.md与04-上报协议与可靠性.md，以及后端知识库02接收链路/05配置；加入请求/响应与错误样例并核对其与1.1..1.3测试一致，按需更新导航，未完成环节保持待验收。

## 2. 阶段 S：持久存储与幂等

- [x] 2.1 新增未占用版本号的ClickHouse峰值表脚本，保存原身份、版本、采集区间及三项Nullable上下文，默认90天TTL；在隔离数据库执行新建及重复执行验证，不改写已发布迁移或删除旧表，并同步后端知识库03及初始化/部署入口的迁移顺序。
- [x] 2.2 更新双存储适配器及写入/查重协调，accepted仅在可靠写入后返回；用存储测试验证批内/并发相同事件、重启/跨接收日重试、响应丢失后重复确认、跨应用同ID及存储失败，统计保持一次且保留原元数据。
- [x] 2.3 用真实持久库验证新旧数据隔离、原峰值列和UUID身份保存、去重不依赖已完成后台合并；同步后端知识库00/03及测试页06的存储边界，给出可复用命令与实际结果，未运行的环境明确标注。

## 3. 阶段 S：运行峰值查询与固定集

- [x] 3.1 summary/trend切换峰值源并以runCount替换sampleCount，按lastSampledAt过滤/分桶、按各指标峰值scene/foreground分别筛选；用MemoryMetricsTests/MemoryMetricsSqlTests覆盖共同分位数、不同有效运行数、缺失与零、跨日和from/to边界，保留Session/应用授权及Agent共用业务入口。
- [x] 3.2 将过滤、去重、精确统计和分桶全部下推数据库并复用查询预算；以至少十万运行记录核验查询计划、时间/应用裁剪、返回桶上限和扫描/内存/超时拒绝，证明应用未加载全部运行，结果与固定集一致，不将合成资源结果扩大为生产容量。
- [x] 3.3 更新docs/datasets/memory-fixed-dataset.md及相关fixture、API查询响应和客户端接入说明；原位同步平台知识库05查询/08测试与后端02查询/06测试，检查sampleCount旧内存语义已移除且共同样例可被契约测试复用。

## 4. 阶段 S：Dashboard 与 Agent/MCP

- [x] 4.1 更新frontend内存类型、API解析、卡片、趋势和筛选说明，读取runCount并标明运行期观测峰值/延迟补报/观测截止归属；补API、页面与组件测试，验证独立上下文、不同运行数、0/null、URL恢复及旧请求取消。
- [x] 4.2 更新MCP内存工具说明、必要结果校验与Agent文档，以共同固定集覆盖get_memory_summary/get_memory_trend，确认runCount、单位、时间桶、筛选、错误和授权与网页一致；不改变其他工具框架或新增写能力。
- [x] 4.3 同步frontend/docs/knowledge-base的01范围、03页面、04模型、06测试及必要导航，docs/api/agent-query-api.md、docs/api/mcp-api.md与mcp-server/README.md中受影响说明；执行前端npm test/typecheck和MCP npm test/typecheck，并检查1280/1440/1920页面及截图，分别标注模拟API与真实后端证据。

## 5. 阶段 S：服务端独立交接检查

- [x] 5.1 执行后端完整 `./backend/gradlew -p backend test`、前端 `npm --prefix frontend test` / `npm --prefix frontend run typecheck`、MCP `npm --prefix mcp-server test` / `npm --prefix mcp-server run typecheck`；记录结果和条件跳过，失败项解决后再交接，不以历史基线替代。
- [x] 5.2 不依赖真实客户端，使用共同样例完成HTTP→ClickHouse→summary/trend及Agent/MCP合成验证，覆盖accepted/duplicate、原元数据和存储错误；交接可用的迁移版本、接口样例、测试命令与本仓库版本，不写入App Key等凭据。
- [x] 5.3 检查根/后端/前端知识库、API和客户端文档的归属同步与本地链接，执行git diff --check和openspec validate report-memory-runtime-peaks --strict；平台状态只声明“服务端独立阶段已验证，客户端与联合验收待完成”，不提前更新为端到端通过。

## 6. 阶段 J：等待客户端后联合验收

本组以阶段S及客户端阶段C完成为前置；可由后续一次联合测试请求统一执行。不得因本仓库独立测试通过而提前勾选。无外部设备/服务条件时明确保留未完成，不能归档。

- [x] 6.1 核对两仓库版本、共同协议、测试应用/包名、实际持久化模式及设备配置，确认客户端频率实验已经完成或在同次设备会话先完成；以J1..J7所需环境可复现作为开始条件。
- [x] 6.2 配合真实客户端执行共同设计J1..J4，核对current/历史队列、服务端请求与存储ID，验证当前轮不上传、跨进程封存、失败重试/响应丢失幂等、升级保留原版本；只在客户端行为和服务端事实均符合时记通过。
- [x] 6.3 执行J5..J7，对比数据库、HTTP、Dashboard和MCP的运行数/分位数/场景/桶，验证历史窗口、非法请求、存储错误与新默认采样开销证据；页面保留真实后端截图，不用模拟数据替代端到端证据。
- [x] 6.4 将联合验收结论与唯一证据入口登记到docs/validation所属索引及平台状态/对应测试页，客户端引用相同证据而非复制矩阵；复核两边任务状态及文档链接，明确全部J通过后才可分别verify/archive。
