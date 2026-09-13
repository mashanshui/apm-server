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

该页面按 SDK `signature` 聚合问题，展示发生次数、影响设备、版本集合、最新引用链和 UTC 趋势；分页前计算占比分母，空趋势桶补零。页面明确标记为“SDK 报告的疑似问题”，不展示泄漏字节或复现率，也没有 HPROF 解析详情入口。筛选和错误语义见[内存泄漏报告 API](../api/memory-leak-reports-api.md)。

内存概览一次返回 PSS、VSS、Java 堆各自的平均值、P50、P90、P95、P99、有效样本数和状态；趋势返回 UTC 时间桶的相同统计值。所有值保持字节，前端换算为两位小数 MiB。所有查询都按去重后的非缺失样本计算，使用 `h=(n-1)*p` 线性插值；空指标和空桶返回 null，不从趋势二次汇总。筛选仅包含时间、应用版本、Android 版本、设备型号、进程名、场景和前后台；不提供 32/64 位、FD 触顶率、多维下钻、对比列表或导出。

FPS 按算法版本隔离并按高到低返回 P50/P90/P99，趋势支持 UTC `hour`/`day`；挂起率先按匿名设备和 UTC 日期合并，再按设备日从低到高计算分位数，趋势只接受 UTC `day`。统一趋势点按时间桶和算法版本排序，使用 `validRecords` 表示当前指标的有效记录数，并只填充当前指标对应的 FPS 或秒/小时前台时长字段。完整字段、错误码和示例见[卡顿监控服务端 API](../api/jank-server-api.md)。

仓库同时提供 Vue 前端。Crash 闭环以及卡顿指标、问题列表、Issue 事件、单事件采样证据和内存指标页面均已落地。卡顿总览/趋势/Issue、区间汇总/趋势/多维区域及内存概览/趋势区域独立处理失败；筛选保存在 URL，详情页严格区分精确消息耗时与采样估算，内存页面明确区分缺失值和零值。浏览器只访问这些 Spring Boot API，不会直接访问 ClickHouse；前端如何消费接口见[API、数据模型与状态管理](../../frontend/docs/knowledge-base/04-API数据模型与状态管理.md)。

查询支持 `from`、`to`（ISO-8601）、`appVersion`、`channel`、`environment`、`osVersion`、`deviceModel`、`fingerprint`、`scene`、`algorithmVersion`、`limit`、`cursor` 和 `timeoutMs`。默认时间范围为最近 24 小时，最大范围 31 天；`limit` 默认 50、最大 500；`timeoutMs` 默认 2000、最大 5000；趋势粒度只支持 `hour` 和 `day`。服务端先根据登录主体和 `app_member` 成员关系校验应用，再进入查询服务；`X-App-Id`、`X-User-App-Ids` 不参与授权。卡顿 ClickHouse SQL 入口带有 `app_id`、时间、行数、超时和白名单维度约束；真实生产聚合性能仍待验收。

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

原方案中的启动、网络、制品上传等路径仍是目标 API，不应当作当前已发布接口。当前没有独立的版本对比 API，Grafana 的版本对比由 Dashboard 直接查询 ClickHouse 原始表完成；卡顿 Issue MVP 仅对应上方列出的服务端接口。

## Dashboard 清单

| Dashboard | 核心内容 | 主要筛选 |
|---|---|---|
| 全局总览 | 事件量、活跃设备、启动 p95、卡顿率、ANR 率、网络错误率 | 时间、应用、版本、渠道、环境 |
| 启动分析 | 冷/温/热启动趋势、分位数、阶段耗时、慢样本 | 版本、设备、系统、启动类型 |
| 网络分析 | 接口耗时、错误率、状态码、DNS/连接/TLS/TTFB 分解 | 域名、路径、网络、版本 |
| 卡顿分析 | 卡顿趋势、页面排行、帧耗时、受影响设备 | 页面、设备、系统、版本 |
| 内存指标分析 | PSS、VSS、Java 堆平均值与 P50/P90/P95/P99 趋势 | 时间、版本、系统、设备、进程、Activity 名称、前后台 |
| 版本对比 | 新旧版本 p95 变化、回归维度、影响范围 | 基准版本、目标版本、渠道 |
| 数据质量 | 上报延迟、拒绝量、Schema 分布、无数据告警 | SDK 版本、Schema、应用 |

## 全局变量

```text
app_id, app_version, channel, environment, os_version,
device_model, network_type, event_type, interval
```

时间范围使用 Grafana 自带时间选择器。当前 JVM Crash Dashboard 实际使用应用、版本、渠道、环境、Android 版本、设备型号和问题指纹变量；`network_type`、`event_type`、`interval` 仍是通用方案变量，不代表当前 Crash API 已提供对应筛选。

## Grafana 查询规则

- 使用专用 ClickHouse 只读账户，仅授权指定库表的 `SELECT`。
- ClickHouse 不暴露公网，Grafana 通过内网访问。
- 目标架构默认查询聚合表；当前 Dashboard JSON 直接查询 `apm_event_raw FINAL`，后端查询服务也通过仓库读取原始事件并在 JVM 内计算。
- 设置查询超时和最大返回行数。
- Dashboard JSON 进入 Git 管理。
- 多租户场景不能仅靠隐藏 `app_id` 变量隔离数据，应使用数据源隔离、行级策略或后端查询代理。

## Grafana 与 Vue 的边界

Grafana 适合趋势、排行、筛选、临时分析和告警；Vue 更适合产品交互和逐层下钻。当前页面范围与产品化待办见[前端产品范围与当前状态](../../frontend/docs/knowledge-base/01-产品范围与当前状态.md)和[维护约定与待办](../../frontend/docs/knowledge-base/08-维护约定与待办.md)。

当前 Vue 页面不通过 `iframe` 嵌入 Grafana，而是直接调用后端 Crash 与卡顿查询 API。Grafana 仍保留为内部分析和运维平台；生产托管细节见[前端部署、安全与运行边界](../../frontend/docs/knowledge-base/07-部署安全与运行边界.md)。

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

已增加以下查询语义：Crash 总览、小时或天趋势、问题排行、按指纹的事件列表和单事件详情。查询支持版本、渠道、环境、Android 版本、设备型号和指纹筛选，并限制时间范围、limit、游标和超时。比例使用 `app_start` 会话分母；分母为空时返回 `null` 和 `denominator_insufficient` 状态；没有任何事件时状态为 `no_data`。

Grafana JSON 位于 `backend/src/main/resources/grafana/dashboards/jvm-crash.json`，包含总览卡片、趋势、问题排行、版本对比、状态说明和下钻链接，当前数据源类型为 `vertamedia-clickhouse-datasource`。Dashboard 验收应覆盖固定数据集中的正常、无数据、分母不足和堆栈下钻状态；这些验收不等同于多租户生产权限或大规模性能验收。
