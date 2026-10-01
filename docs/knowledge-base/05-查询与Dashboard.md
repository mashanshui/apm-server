# 查询与 Dashboard

## 查询 API

当前已实现的 JVM Crash 查询接口如下：

```http
GET /api/v1/apps/{appId}/crashes/overview
GET /api/v1/apps/{appId}/crashes/trend?interval=hour|day
GET /api/v1/apps/{appId}/crashes/issues
GET /api/v1/apps/{appId}/crashes/issues/{fingerprint}/events
GET /api/v1/apps/{appId}/crashes/events/{eventId}
```

当前已实现的服务端卡顿 Issue MVP 查询接口如下：

```http
GET /api/v1/apps/{appId}/janks/overview
GET /api/v1/apps/{appId}/janks/trend?interval=hour|day
GET /api/v1/apps/{appId}/janks/issues
GET /api/v1/apps/{appId}/janks/issues/{fingerprint}/events
GET /api/v1/apps/{appId}/janks/events/{eventId}
```

卡顿总览返回事件数、受影响会话/设备、可归组事件数和精确消息耗时分位数；Issue 和事件详情额外返回明确标记的采样估算耗时、覆盖/空洞和采集质量。卡顿查询默认使用成员 Session 授权，状态区分 `ok` 与 `no_data`。FPS、设备日挂起率和白名单多维查询已实现，状态额外区分 `no_valid_data` 与 `denominator_insufficient`。

当前卡顿个例 ClickHouse 路径先按时间升序读取最多 `limit` 条事实，再在 Java 中统计、分桶和分页；默认 50、最大 500 会同时截断统计输入。超过上限时总览、趋势和 Issue 排行可能不完整，事件页也不能保证遍历范围内全部记录。内存适配器没有该前置截断，固定小样本不能证明两者的大范围等价。此限制不适用于 FPS/挂起率的数据库聚合入口，也不能套用 Crash 下推查询结论；代码依据见[后端查询链路](../../backend/docs/knowledge-base/02-接收解析与查询链路.md)，修复待办见[卡顿协议与性能](13-待确认事项.md#卡顿协议与性能)。

指标查询接口为：

```http
GET /api/v1/apps/{appId}/jank-metrics/fps
GET /api/v1/apps/{appId}/jank-metrics/suspension-rate
GET /api/v1/apps/{appId}/jank-metrics/trend?metric=fps|suspension_rate&interval=hour|day
GET /api/v1/apps/{appId}/jank-metrics/dimensions?metric=fps&dimension=scene
```

内存指标查询接口为：

```http
GET /api/v1/apps/{appId}/memory-metrics/summary
GET /api/v1/apps/{appId}/memory-metrics/trend?metric=pss|vss|java_heap&interval=hour|day
```

内存异常报告查询接口为：

```http
GET /api/v1/apps/{appId}/memory-leaks/issues
GET /api/v1/apps/{appId}/memory-leaks/trend?interval=5m|hour|day
```

该页面按 SDK `signature` 聚合问题，展示发生次数、影响设备、版本集合、最新引用链和 UTC 趋势；分页前计算占比分母，空趋势桶补零。页面明确标记为“SDK 报告的疑似问题”，不展示泄漏字节或复现率，也没有 HPROF 解析详情入口。当前报告在 ClickHouse 按应用、时间和维度过滤后读入 Java 聚合及分页，尚未具备 Crash 查询同等的数据库聚合和资源预算，不能把页面分页等同于有界数据库读取。筛选和错误语义见[内存泄漏报告 API](../api/memory-leak-reports-api.md)。

内存概览一次返回 PSS、VSS、Java 堆各自的平均值、P50、P90、P95、P99、有效样本数和状态；趋势返回 UTC 时间桶的相同统计值。所有值保持字节，前端换算为两位小数 MiB。所有查询都按去重后的非缺失样本计算，使用 `h=(n-1)*p` 线性插值；空指标和空桶返回 null，不从趋势二次汇总。筛选仅包含时间、应用版本、Android 版本、设备型号、进程名、场景和前后台；不提供 32/64 位、FD 触顶率、多维下钻、对比列表或导出。

FPS 按算法版本隔离并按高到低返回 P50/P90/P99，趋势支持 UTC `hour`/`day`；挂起率先按匿名设备和 UTC 日期合并，再按设备日从低到高计算分位数，趋势只接受 UTC `day`。统一趋势点按时间桶和算法版本排序，使用 `validRecords` 表示当前指标的有效记录数，并只填充当前指标对应的 FPS 或秒/小时前台时长字段。完整字段、错误码和示例见[卡顿监控服务端 API](../api/jank-server-api.md)。

仓库同时提供 Vue 前端。Crash 闭环以及卡顿指标、问题列表、Issue 事件、单事件采样证据和内存指标页面均已落地。卡顿总览/趋势/Issue、区间汇总/趋势/多维区域及内存概览/趋势区域独立处理失败；筛选保存在 URL，详情页严格区分精确消息耗时与采样估算，内存页面明确区分缺失值和零值。浏览器只访问这些 Spring Boot API，不会直接访问 ClickHouse；前端如何消费接口见[API、数据模型与状态管理](../../frontend/docs/knowledge-base/04-API数据模型与状态管理.md)。

各域只接受自己的筛选参数，不能把所有参数用于任意端点。Crash/卡顿网页列表使用 `limit/cursor`，默认 50、最大 500；Agent 列表另限默认 20、最大 100。内存指标支持进程/场景/前后台等筛选，不使用列表分页；内存异常问题使用 `page`（从 1 开始）/`pageSize`（默认 20、最大 100），趋势接受 `5m/hour/day` 且最多 2000 桶。Crash/卡顿与内存指标趋势接受 `hour/day`，挂起率仅接受 `day`。默认时间窗为最近 24 小时、最大 31 天；Crash/卡顿及内存指标的 `timeoutMs` 默认 2000、最大 5000，内存异常不接受该参数。完整白名单以各领域 API 为准。服务端先根据登录主体和 `app_member` 成员关系校验应用，再进入查询服务；`X-App-Id`、`X-User-App-Ids` 不参与授权。Crash 的 ClickHouse 查询已下推时间、维度、精确去重与分页，并设置扫描、内存和 HTTP 响应预算；[Crash 查询性能基线](../../backend/docs/knowledge-base/crash-query-performance-baseline.md)记录合成规模与资源拒绝边界。卡顿 ClickHouse SQL 入口带有 `app_id`、时间、行数、超时和白名单维度约束；真实生产聚合性能仍待验收。

网页登录与项目管理已提供以下接口，完整示例见[登录与项目管理 API](../api/app-api.md)：

```http
GET   /api/v1/session
POST  /api/v1/auth/login
POST  /api/v1/auth/logout
GET   /api/v1/apps?query=
POST  /api/v1/apps
GET   /api/v1/apps/{appId}
PATCH /api/v1/apps/{appId}
```

原方案中的启动耗时、网络和构建自动上传等路径仍是目标 API，不应当作已发布接口；网页 mapping 上传和内存报告上传已经实现。当前没有独立的版本对比 API，Grafana 的版本对比由 Dashboard 直接查询 ClickHouse 原始表完成；卡顿 Issue MVP 仅对应上方列出的服务端接口。

## Dashboard 当前与目标清单

当前 Vue 提供 Crash、卡顿、内存指标和 SDK 内存异常页面，Grafana 提供 JVM Crash JSON；下表中的全局总览、启动、网络和数据质量页面仍是目标范围。Grafana JSON 的版本对比不代表已有 Vue 版本对比页面。

| Dashboard | 核心内容 | 主要筛选 |
|---|---|---|
| 全局总览（目标） | 事件量、活跃设备、启动 p95、卡顿率、ANR 率、网络错误率 | 时间、应用、版本、渠道、环境 |
| 启动分析（目标） | 冷/温/热启动趋势、分位数、阶段耗时、慢样本 | 版本、设备、系统、启动类型 |
| 网络分析（目标） | 接口耗时、错误率、状态码、DNS/连接/TLS/TTFB 分解 | 域名、路径、网络、版本 |
| 卡顿分析 | 精确消息耗时、问题/事件/采样证据、场景 FPS、设备日挂起率和受影响设备 | 场景、设备、系统、版本、算法 |
| 内存指标分析 | PSS、VSS、Java 堆平均值与 P50/P90/P95/P99 趋势 | 时间、版本、系统、设备、进程、Activity 名称、前后台 |
| SDK 内存异常 | signature 问题聚合、发生/设备趋势和引用链展开 | 时间、版本、设备、进程、场景、厂商、SDK 等正式白名单 |
| 版本对比（当前仅 Crash Grafana 资源） | 各版本启动/崩溃会话、每千会话崩溃率和受影响设备；性能分位数回归仍为目标 | 当前资源按应用版本分组，复用时间/应用/渠道等变量 |
| 数据质量（目标） | 上报延迟、拒绝量、Schema 分布、无数据告警 | SDK 版本、Schema、应用 |

## 全局变量

```text
app_id, app_version, channel, environment, os_version,
device_model, network_type, event_type, interval
```

时间范围使用 Grafana 自带时间选择器。当前 JVM Crash Dashboard 实际使用应用、版本、渠道、环境、Android 版本、设备型号和问题指纹变量；`network_type`、`event_type`、`interval` 仍是通用方案变量，不代表当前 Crash API 已提供对应筛选。

## Grafana 查询规则

- 使用专用 ClickHouse 只读账户，仅授权指定库表的 `SELECT`。
- ClickHouse 不暴露公网，Grafana 通过内网访问。
- 目标架构默认查询聚合表；当前 Dashboard JSON 直接查询 `apm_event_raw FINAL`。后端 Crash 查询已在 ClickHouse 内完成过滤、统计和分页，卡顿与内存各自保留其领域查询实现。
- 设置查询超时和最大返回行数。
- Dashboard JSON 进入 Git 管理。
- 多租户场景不能仅靠隐藏 `app_id` 变量隔离数据，应使用数据源隔离、行级策略或后端查询代理。

## Grafana 与 Vue 的边界

Grafana 适合趋势、排行、筛选、临时分析和告警；Vue 更适合产品交互和逐层下钻。当前页面范围与产品化待办见[前端产品范围与当前状态](../../frontend/docs/knowledge-base/01-产品范围与当前状态.md)和[维护约定与待办](../../frontend/docs/knowledge-base/08-维护约定与待办.md)。

当前 Vue 页面不通过 `iframe` 嵌入 Grafana，而是直接调用后端 Crash、卡顿、内存指标和内存异常查询 API。Grafana 仍保留为内部分析和运维平台；生产托管细节见[前端部署、安全与运行边界](../../frontend/docs/knowledge-base/07-部署安全与运行边界.md)。

## 告警候选

- 启动耗时 p95 超过阈值。
- 网络错误率超过阈值。
- 某版本 ANR 率高于基准版本。
- 卡顿率在短时间窗口内明显增长。
- 连续一段时间没有收到数据。
- 上报拒绝率超过阈值。
- ClickHouse 磁盘使用率超过阈值。

阈值、评估窗口、通知渠道和恢复条件属于待确认的运维参数。

## JVM Crash 查询与 Dashboard 实现

已增加以下查询语义：Crash 总览、小时或天趋势、问题排行、按指纹的事件列表和单事件详情。查询支持版本、渠道、环境、Android 版本、设备型号和指纹筛选，并限制时间范围、limit、游标和超时。比例使用 `app_start` 会话分母；分母为空时返回 `null` 和 `denominator_insufficient` 状态；没有任何事件时状态为 `no_data`。Crash 事件详情还会按当前 `appId + buildId` mapping 在请求内 Retrace，结果只存在响应中，不写回事件或缓存；列表、上传和替换契约见[符号表管理 API](../api/symbol-api.md)。

Grafana JSON 位于 `backend/src/main/resources/grafana/dashboards/jvm-crash.json`，包含总览卡片、趋势、问题排行、版本对比、状态说明和下钻链接，当前数据源类型为 `vertamedia-clickhouse-datasource`。Dashboard 验收应覆盖固定数据集中的正常、无数据、分母不足和堆栈下钻状态；这些验收不等同于多租户生产权限或大规模性能验收。

## Agent 只读查询边界

只读 Agent HTTP 入口复用 Crash、卡顿和内存查询服务，共 19 个 GET 路由；MCP 提供对应 19 个工具。Crash 已改为 ClickHouse 聚合下推、逻辑事件去重和版本化游标。具体业务字段仍以各领域 API 为准；认证、额度和证据边界见 [Agent HTTP API](../api/agent-query-api.md) 与 [MCP API](../api/mcp-api.md)。本地真实客户端全链路已验收；云端部署及入口冒烟已完成，有效 Token 的云端工具查询和生产容量尚未验收。
